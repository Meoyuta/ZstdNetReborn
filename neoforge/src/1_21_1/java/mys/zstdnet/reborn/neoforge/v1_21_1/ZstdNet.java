package mys.zstdnet.reborn.neoforge.v1_21_1;

import mys.zstdnet.reborn.client.ZstdNetClient;
import mys.zstdnet.reborn.core.HostPort;
import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.benchmark.CompressionBenchmark;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.BufferedWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import mys.zstdnet.reborn.neoforge.MeasuredLatencyClientState;

@Mod(ZstdNet.MOD_ID)
public final class ZstdNet {
    public static final String MOD_ID = "zstdnet";

    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    private final ZstdNetLogger logger = new Slf4jLogger(LOGGER);
    private SamePortZstdInjector injector;
    private ZstdDictionaryTrainer dictionaryTrainer;
    private CompressionBenchmark benchmark;
    private volatile net.minecraft.server.level.ServerPlayer pendingBenchmarkRequester;
    private final AtomicInteger compressionLevel = new AtomicInteger(9);
    private final AtomicInteger benchmarkRunCount = new AtomicInteger();
    private volatile long runtimeStartedNanos;
    private final AtomicLong lastTickNanos = new AtomicLong();
    private final AtomicLong maxTickNanos = new AtomicLong();
    private final AtomicLong tickCount = new AtomicLong();
    private volatile long serverTickStartedNanos;
    private final Map<java.util.UUID, LatencyObservation> latencyObservations = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, Probe> debugProbes = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, Double> latestLatencies = new ConcurrentHashMap<>();
    private static final long LATENCY_PROBE_TIMEOUT_NANOS = 5_000_000_000L;
    private long lastLatencyProbeTick;
    private final java.util.Map<java.util.UUID, String> infoOverlays = new java.util.HashMap<>();
    private long lastOverlayRefreshTick;
    // One store owns the current immutable dictionary for the lifetime of this mod instance.
    private final ZstdDictionaryStore dictionaryStore = new ZstdDictionaryStore(
        FMLPaths.CONFIGDIR.get().resolve("zstdnet").resolve("dict").resolve("dictionary.zdict"), logger);
    private boolean accepting;

    public ZstdNet(IEventBus modEventBus) {
        modEventBus.addListener(this::registerPayloads);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ZstdNetClient.init(FMLPaths.CONFIGDIR.get(), logger);
            ZstdNetClient.setDictionaryDownloadListenerFactory(DictionaryDownloadScreen::listener);
            modEventBus.addListener(InfoOverlaySelectionScreen::registerKeyMappings);
            NeoForge.EVENT_BUS.addListener(ZstdInfoOverlay::render);
            NeoForge.EVENT_BUS.addListener(ZstdInfoOverlay::tick);
            NeoForge.EVENT_BUS.addListener(InfoOverlaySelectionScreen::onClientTick);
        }

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) ->
            serverTickStartedNanos = System.nanoTime());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> {
            ZstdCommands.tick(event.getServer(), this);
            refreshInfoOverlays(event.getServer());
            refreshMeasuredLatencies(event.getServer());
            long tickNanos = serverTickStartedNanos == 0L
                ? 0L
                : Math.max(0L, System.nanoTime() - serverTickStartedNanos);
            lastTickNanos.set(tickNanos);
            maxTickNanos.accumulateAndGet(tickNanos, Math::max);
            tickCount.incrementAndGet();
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                ZstdCommands.prompt(player, this);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) -> {
            var id = event.getEntity().getUUID();
            debugProbes.remove(id);
            latestLatencies.remove(id);
            broadcastMeasuredLatency(id, -1.0D);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
            ZstdCommands.register(event, this));
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(BenchmarkInfoPayload.TYPE, BenchmarkInfoPayload.STREAM_CODEC,
             (payload, context) -> BenchmarkInfoClientState.receive(payload));
        event.registrar("1").playToClient(ManagementStatusPayload.TYPE, ManagementStatusPayload.STREAM_CODEC,
            (payload, context) -> ManagementStatusClientState.receive(payload));
        event.registrar("1").playToClient(DictionaryStatusPayload.TYPE, DictionaryStatusPayload.STREAM_CODEC,
            (payload, context) -> DictionaryStatusClientState.receive(payload));
        event.registrar("1").playToClient(InfoOverlayControlPayload.TYPE, InfoOverlayControlPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> ZstdInfoOverlay.control(payload)));
        event.registrar("1").playToServer(InfoOverlaySelectionPayload.TYPE, InfoOverlaySelectionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    selectInfoOverlay(player, payload.selection());
                }
            }));
        event.registrar("1").playToClient(DebugPingPayload.TYPE, DebugPingPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> DebugPingClientState.receive(payload)));
        event.registrar("1").playToClient(MeasuredLatencyPayload.TYPE, MeasuredLatencyPayload.STREAM_CODEC,
             (payload, context) -> context.enqueueWork(() -> MeasuredLatencyClientState.receive(payload.playerId(), payload.millis())));
        event.registrar("1").playToServer(DebugPongPayload.TYPE, DebugPongPayload.STREAM_CODEC,
            (payload, context) -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    receiveDebugPong(player, payload, System.nanoTime());
                }
            });
    }

    private synchronized void onServerStarted(ServerStartedEvent event) {
        // Compression is enabled automatically for every dedicated-server session.
        if (event.getServer().isDedicatedServer()) start(event.getServer());
    }

    synchronized boolean start(MinecraftServer server) {
        if (!server.isDedicatedServer()) return false;
        if (injector != null) {
            if (!accepting) {
                injector.inject();
                accepting = true;
                runtimeStartedNanos = System.nanoTime();
            }
            return true;
        }
        var port = server.getPort();
        var config = ZstdNetConfig.defaults(
            new HostPort("0.0.0.0", port),
            new HostPort("same-port", port)
        );
        try {
            dictionaryStore.enableNaming();
        } catch (java.io.IOException e) {
            LOGGER.error("Could not restore dictionary naming records", e);
            return false;
        }
        dictionaryStore.loadSelected();

        var trainer = new ZstdDictionaryTrainer(dictionaryStore, logger,
            ZstdDictionaryTrainer.DOWNLINK_DICTIONARY_BYTES, true);
        try {
            if (benchmark == null) {
                benchmark = new CompressionBenchmark(
                    FMLPaths.CONFIGDIR.get().resolve("zstdnet").resolve("server.properties"),
                    logger,
                    () -> injector == null ? 0 : injector.snapshot().connections(),
                    compressionLevel::get,
                    compressionLevel::set,
                    dictionaryStore::dictionary
                );
                benchmark.setCompletionListener(this::onBenchmarkComplete);
            }
            var next = new SamePortZstdInjector(
                server,
                config,
                logger,
                dictionaryStore,
                trainer,
                benchmark,
                compressionLevel::get
            );
            next.inject();
            injector = next;
            dictionaryTrainer = trainer;
            accepting = true;
            runtimeStartedNanos = System.nanoTime();
            return true;
        } catch (Exception e) {
            trainer.close();
            LOGGER.error("Could not inject ZstdNet into the Minecraft Netty listener", e);
            return false;
        }
    }

    private synchronized void onServerStopping(ServerStoppingEvent event) {
        debugProbes.clear();
        latestLatencies.clear();
        runtimeStartedNanos = 0L;
        benchmarkRunCount.set(0);
        if (benchmark != null) {
            benchmark.close();
            benchmark = null;
        }
        pendingBenchmarkRequester = null;
        if (dictionaryTrainer != null) {
            dictionaryStore.beginShutdown();
            dictionaryTrainer.finishAndClose();
        }
        if (injector != null) {
            injector.close();
            injector = null;
        }
        if (dictionaryTrainer != null) {
            dictionaryTrainer = null;
        }
        accepting = false;
    }

    synchronized void stop() {
        if (dictionaryTrainer != null) dictionaryTrainer.abort();
        if (injector != null && accepting) {
            injector.close();
            accepting = false;
        }
    }

    synchronized boolean reload(MinecraftServer server) {
        if (!start(server)) return false;
        dictionaryTrainer.abort();
        dictionaryStore.loadSelected();
        return true;
    }

    synchronized boolean isRunning() {
        return accepting;
    }

    synchronized int dictionaryConnections() {
        return injector == null ? 0 : injector.dictionaryConnections();
    }

    synchronized int selectedDictionaryConnections() {
        var dictionary = dictionaryStore.dictionary();
        return injector == null || dictionary == null ? 0 : injector.dictionaryConnections(dictionary.id());
    }

    synchronized boolean startBenchmark(net.minecraft.server.level.ServerPlayer requester) {
        if (benchmark == null) return false;
        var started = benchmark.start();
        if (started && requester != null) pendingBenchmarkRequester = requester;
        return started;
    }

    private void onBenchmarkComplete(CompressionBenchmark.Result result) {
        if (!"complete".equals(result.state())) return;
        benchmarkRunCount.incrementAndGet();
        final var requester = pendingBenchmarkRequester;
        pendingBenchmarkRequester = null;
        if (requester != null && requester.getServer() != null) {
            requester.getServer().execute(() -> sendBenchmarkInfo(requester));
        }
    }

    synchronized void sendBenchmarkInfo(net.minecraft.server.level.ServerPlayer player) {
        if (benchmark == null || player == null || !player.connection.isAcceptingMessages()) return;
        var result = benchmark.result();
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new BenchmarkInfoPayload(result.state(), result.level(), result.automatic(),
                result.compressionPercent(), result.codecMillis(), result.estimatedMillis(),
                result.samples(), result.completedAt(), result.intervalMinutes())));
    }

    synchronized void sendManagementStatus(net.minecraft.server.level.ServerPlayer player) {
        if (player == null || !player.connection.isAcceptingMessages()) return;
        var stats = snapshot();
        var latency = latestLatencies.getOrDefault(player.getUUID(), Double.NaN);
        var selected = dictionaryStore.dictionary();
        var dictionary = selected == null ? "none" : selectedDictionaryDescription();
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new ManagementStatusPayload(isRunning() ? "running" : "stopped", player.getServer().getPort(),
                stats.wireUpBytes(), stats.rawUpBytes(), stats.wireDownBytes(), stats.rawDownBytes(),
                stats.wireUpRate(), stats.rawUpRate(), stats.wireDownRate(), stats.rawDownRate(),
                stats.ratioPercent(), stats.connections(), compressionLevel.get(), dictionary,
                dictionaryConnections(), selectedDictionaryConnections(), latency,
                runtimeSeconds(), benchmarkRunCount.get())));
    }

    synchronized void sendDictionaryStatus(net.minecraft.server.level.ServerPlayer player, String mode) {
        if (player == null || !player.connection.isAcceptingMessages() || dictionaryTrainer == null) return;
        var status = dictionaryTrainer.status();
        var selected = dictionaryStore.dictionary();
        var description = selected == null ? "none" : selectedDictionaryDescription();
        var path = dictionaryStore.selectedPath() == null ? "none" : dictionaryStore.selectedPath().toString();
        var available = java.util.List.<String>of();
        try {
            available = dictionaryStore.available();
        } catch (IOException e) {
            logger.warn("Could not enumerate dictionaries: " + e.getMessage());
        }
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new DictionaryStatusPayload(mode, description, path, dictionaryStatusText(status),
                status.sampleCount(), status.sampleBytes(), Math.max(0, status.remainingMillis() / 1000),
                available)));
    }

    synchronized void selectInfoOverlay(net.minecraft.server.level.ServerPlayer player, String selection) {
        var current = infoOverlays.get(player.getUUID());
        if (selection.equals("off") || !java.util.Set.of("status", "benchmark", "dictionary").contains(selection)) {
            infoOverlays.remove(player.getUUID());
            if (current != null) sendOverlayControl(player, false);
            return;
        }
        infoOverlays.put(player.getUUID(), selection);
        sendOverlayControl(player, true);
        sendOverlaySnapshot(player, selection);
    }

    void measureDirectLatency(net.minecraft.server.level.ServerPlayer player, DoubleConsumer callback) {
        if (player == null || !player.connection.isAcceptingMessages()) return;
        long nonce = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        debugProbes.put(player.getUUID(), new Probe(nonce, System.nanoTime(), callback));
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new DebugPingPayload(nonce)));
    }

    private void receiveDebugPong(net.minecraft.server.level.ServerPlayer player, DebugPongPayload payload, long receivedNanos) {
        var probe = debugProbes.get(player.getUUID());
        if (probe == null || probe.nonce() != payload.nonce()) return;
        debugProbes.remove(player.getUUID(), probe);
        double networkMillis = (receivedNanos - probe.startedNanos()) / 1_000_000.0D;
        long queuedNanos = Math.max(0L, System.nanoTime() - receivedNanos);
        double millis = (networkMillis + queuedNanos / 1_000_000.0D);
        latencyObservations.put(player.getUUID(), new LatencyObservation(networkMillis, queuedNanos / 1_000_000.0D, millis));
        player.getServer().execute(() -> probe.callback().accept(millis));
    }

    synchronized void refreshInfoOverlays(MinecraftServer server) {
        if (server.getTickCount() - lastOverlayRefreshTick < 20) return;
        lastOverlayRefreshTick = server.getTickCount();
        infoOverlays.entrySet().removeIf(entry -> {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.connection.isAcceptingMessages()) return true;
            sendOverlaySnapshot(player, entry.getValue());
            return false;
        });
    }

    synchronized void refreshMeasuredLatencies(MinecraftServer server) {
        if (server.getTickCount() - lastLatencyProbeTick < 20) return;
        lastLatencyProbeTick = server.getTickCount();
        var now = System.nanoTime();
        for (var entry : debugProbes.entrySet()) {
            var probe = entry.getValue();
            if (now - probe.startedNanos() >= LATENCY_PROBE_TIMEOUT_NANOS
                    && debugProbes.remove(entry.getKey(), probe)) {
                latestLatencies.remove(entry.getKey());
                broadcastMeasuredLatency(entry.getKey(), -1.0D);
            }
        }
        for (var player : server.getPlayerList().getPlayers()) {
            if (!player.connection.isAcceptingMessages() || debugProbes.containsKey(player.getUUID())) continue;
            measureDirectLatency(player, millis -> {
                latestLatencies.put(player.getUUID(), millis);
                broadcastMeasuredLatency(player.getUUID(), millis);
            });
        }
    }

    private void broadcastMeasuredLatency(java.util.UUID playerId, double millis) {
        var packet = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new MeasuredLatencyPayload(playerId, millis));
        if (injector == null || injector.server() == null) return;
        for (var viewer : injector.server().getPlayerList().getPlayers()) {
            if (viewer.connection.isAcceptingMessages()) viewer.connection.send(packet);
        }
    }

    private void sendOverlaySnapshot(net.minecraft.server.level.ServerPlayer player, String type) {
        switch (type) {
            case "status" -> sendManagementStatus(player);
            case "benchmark" -> sendBenchmarkInfo(player);
            case "dictionary" -> sendDictionaryStatus(player, "status");
        }
    }

    private static void sendOverlayControl(net.minecraft.server.level.ServerPlayer player, boolean open) {
        if (player.connection.isAcceptingMessages()) player.connection.send(
            new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new InfoOverlayControlPayload(open)));
    }

    private String selectedDictionaryDescription() {
        var selected = dictionaryStore.dictionary();
        var path = dictionaryStore.selectedPath();
        var name = path == null ? "unknown" : path.getFileName().toString();
        if (name.endsWith(".zdict")) name = name.substring(0, name.length() - 6);
        return name + " (id:" + Long.toUnsignedString(selected.id()) + ", " + selected.size() + " bytes)";
    }

    private static String dictionaryStatusText(ZstdDictionaryTrainer.Status status) {
        if (status.finalizing()) return "training";
        if (status.training()) return "collecting";
        return status.result();
    }

    synchronized CompressionBenchmark.Result benchmarkResult() {
        return benchmark == null ? CompressionBenchmark.Result.skipped("not_running", true,
            compressionLevel.get(), 30) : benchmark.result();
    }

    synchronized boolean benchmarkRunning() {
        return benchmark != null && benchmark.isRunning();
    }

    synchronized int benchmarkSampleCount() {
        return benchmark == null ? 0 : benchmark.sampleCount();
    }

    synchronized int compressionLevel() {
        return compressionLevel.get();
    }

    synchronized void setTemporaryCompressionLevel(int level) {
        if (benchmark == null) throw new IllegalStateException("compression benchmark is not initialized");
        benchmark.setTemporaryLevel(level);
    }

    synchronized void setAutomaticCompression() throws IOException {
        if (benchmark == null) throw new IllegalStateException("compression benchmark is not initialized");
        benchmark.setAutomatic();
    }

    synchronized void setBenchmarkInterval(int minutes) throws IOException {
        if (benchmark == null) throw new IllegalStateException("compression benchmark is not initialized");
        benchmark.setInterval(minutes);
    }

    synchronized int benchmarkInterval() {
        return benchmark == null ? 30 : benchmark.intervalMinutes();
    }

    synchronized boolean automaticCompression() {
        return benchmark == null || benchmark.automatic();
    }

    synchronized long runtimeSeconds() {
        var started = runtimeStartedNanos;
        return !accepting || started == 0L
            ? 0L
            : Math.max(0L, (System.nanoTime() - started) / 1_000_000_000L);
    }

    synchronized int benchmarkRunCount() {
        return benchmarkRunCount.get();
    }

    synchronized Path writeDebugReport(net.minecraft.server.level.ServerPlayer requester) throws IOException {
        Path directory = FMLPaths.CONFIGDIR.get().resolve("debug");
        Files.createDirectories(directory);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault()).format(Instant.now());
        Path report = directory.resolve("zstdnet-debug-" + timestamp + ".log");
        var stats = snapshot();
        var result = benchmarkResult();
        var runtime = ManagementFactory.getRuntimeMXBean();
        var memory = ManagementFactory.getMemoryMXBean();
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        var threads = ManagementFactory.getThreadMXBean();
        try (BufferedWriter out = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            out.write("ZstdNet diagnostic snapshot\n");
            out.write("generated_at=" + Instant.now() + "\n");
            out.write("requester=" + requester.getGameProfile().getName() + "\n");
            out.write("zstdnet_running=" + isRunning() + "\n");
            out.write("runtime_seconds=" + runtimeSeconds() + "\n");
            out.write("benchmark_runs=" + benchmarkRunCount() + "\n");
            out.write("compression_level=" + compressionLevel() + "\n");
            out.write("connections_active=" + stats.connections() + "\n");
            out.write("connections_total=" + stats.totalConnections() + "\n");
            out.write("bytes_raw_upload=" + stats.rawUpBytes() + "\n");
            out.write("bytes_wire_upload=" + stats.wireUpBytes() + "\n");
            out.write("bytes_raw_download=" + stats.rawDownBytes() + "\n");
            out.write("bytes_wire_download=" + stats.wireDownBytes() + "\n");
            out.write("rate_raw_upload=" + stats.rawUpRate() + "\n");
            out.write("rate_wire_upload=" + stats.wireUpRate() + "\n");
            out.write("rate_raw_download=" + stats.rawDownRate() + "\n");
            out.write("rate_wire_download=" + stats.wireDownRate() + "\n");
            out.write("compression_ratio_percent=" + String.format(Locale.ROOT, "%.4f", stats.ratioPercent()) + "\n");
            out.write("dictionary_connections=" + dictionaryConnections() + "\n");
            out.write("selected_dictionary_connections=" + selectedDictionaryConnections() + "\n");
            out.write("benchmark_state=" + result.state() + "\n");
            out.write("benchmark_level=" + result.level() + "\n");
            out.write("benchmark_samples=" + result.samples() + "\n");
            out.write("benchmark_interval_minutes=" + result.intervalMinutes() + "\n");
            out.write("benchmark_running=" + benchmarkRunning() + "\n");
            out.write("server_tick_last_ms=" + nanosToMillis(lastTickNanos.get()) + "\n");
            out.write("server_tick_max_ms=" + nanosToMillis(maxTickNanos.get()) + "\n");
            out.write("server_tick_samples=" + tickCount.get() + "\n");
            out.write("jvm_name=" + runtime.getVmName() + "\n");
            out.write("jvm_version=" + runtime.getVmVersion() + "\n");
            out.write("java_version=" + System.getProperty("java.version") + "\n");
            out.write("os_name=" + operatingSystem.getName() + "\n");
            out.write("os_arch=" + operatingSystem.getArch() + "\n");
            out.write("cpu_count=" + operatingSystem.getAvailableProcessors() + "\n");
            out.write("heap_used_bytes=" + memory.getHeapMemoryUsage().getUsed() + "\n");
            out.write("heap_committed_bytes=" + memory.getHeapMemoryUsage().getCommitted() + "\n");
            out.write("heap_max_bytes=" + memory.getHeapMemoryUsage().getMax() + "\n");
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                out.write("gc." + gc.getName() + ".collections=" + gc.getCollectionCount() + "\n");
                out.write("gc." + gc.getName() + ".time_ms=" + gc.getCollectionTime() + "\n");
            }
            out.write("\nPlayers\n");
            for (var player : injector == null ? java.util.List.<net.minecraft.server.level.ServerPlayer>of()
                    : injector.server().getPlayerList().getPlayers()) {
                var observation = latencyObservations.get(player.getUUID());
                var pending = debugProbes.get(player.getUUID());
                out.write("player=" + player.getGameProfile().getName()
                    + " uuid=" + player.getUUID()
                    + " measured_rtt_ms=" + (observation == null ? "NA" : format(observation.totalMillis()))
                    + " network_rtt_ms=" + (observation == null ? "NA" : format(observation.networkMillis()))
                    + " main_thread_queue_ms=" + (observation == null ? "NA" : format(observation.queueMillis()))
                    + " pending_probe_age_ms=" + (pending == null ? "0"
                        : format((System.nanoTime() - pending.startedNanos()) / 1_000_000.0D))
                    + "\n");
            }
            out.write("\nThreads\n");
            writeThreadSnapshot(out, threads);
        }
        return report;
    }

    private static void writeThreadSnapshot(BufferedWriter out, ThreadMXBean threads) throws IOException {
        var ids = threads.getAllThreadIds();
        var top = new java.util.ArrayList<Long>();
        for (long id : ids) top.add(id);
        top.sort(Comparator.comparingLong((Long id) -> {
            long cpu = threads.getThreadCpuTime(id);
            return cpu < 0L ? 0L : cpu;
        }).reversed());
        int limit = Math.min(12, top.size());
        for (int i = 0; i < limit; i++) {
            long id = top.get(i);
            ThreadInfo info = threads.getThreadInfo(id, 8);
            if (info == null) continue;
            out.write("thread_id=" + id + " name=" + info.getThreadName()
                + " state=" + info.getThreadState()
                + " cpu_ms=" + nanosToMillis(Math.max(0L, threads.getThreadCpuTime(id))) + "\n");
            for (var frame : info.getStackTrace()) out.write("  at " + frame + "\n");
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    synchronized mys.zstdnet.reborn.core.stats.TrafficStats.Snapshot snapshot() {
        return injector == null ? new mys.zstdnet.reborn.core.stats.TrafficStats().snapshot() : injector.snapshot();
    }

    synchronized ZstdDictionaryStore dictionaryStore() {
        return dictionaryStore;
    }

    synchronized ZstdDictionaryTrainer dictionaryTrainer() {
        return dictionaryTrainer;
    }

    synchronized void disconnectForDictionarySwitch() {
        if (injector == null) return;
        var server = injector.server();
        if (server == null) return;
        var message = net.minecraft.network.chat.Component.translatable("zstdnet.disconnect.dictionary_switched");
        for (var player : server.getPlayerList().getPlayers()) {
            if (player.connection.isAcceptingMessages()) player.connection.disconnect(message);
        }
    }

    void commandFailed(String action, Exception error) {
        LOGGER.error("ZstdNet command failed: " + action, error);
    }

    private record Slf4jLogger(Logger logger) implements ZstdNetLogger {
        @Override
        public void debug(String message) {
            logger.debug(message);
        }

        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warn(message);
        }

        @Override
        public void error(String message) {
            logger.error(message);
        }
    }

    private record Probe(long nonce, long startedNanos, DoubleConsumer callback) {}
    private record LatencyObservation(double networkMillis, double queueMillis, double totalMillis) {}
}
