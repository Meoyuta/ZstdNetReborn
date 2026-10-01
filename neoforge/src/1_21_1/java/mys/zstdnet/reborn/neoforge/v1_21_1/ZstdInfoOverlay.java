package mys.zstdnet.reborn.neoforge.v1_21_1;

import mys.zstdnet.reborn.neoforge.MeasuredLatencyClientState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Non-modal, F3-style information display shared by the three status commands.
 */
final class ZstdInfoOverlay {
    private static volatile Kind kind;
    private static volatile Object payload;
    private ZstdInfoOverlay() {
    }

    static void management(ManagementStatusPayload value) {
        set(Kind.MANAGEMENT, value);
    }

    static void benchmark(BenchmarkInfoPayload value) {
        set(Kind.BENCHMARK, value);
    }

    static void dictionary(DictionaryStatusPayload value) {
        set(Kind.DICTIONARY, value);
    }

    static void control(InfoOverlayControlPayload value) {
        if (!value.open()) clear();
    }

    private static void set(Kind nextKind, Object nextPayload) {
        var client = Minecraft.getInstance();
        kind = nextKind;
        payload = nextPayload;
    }

    static void render(RenderGuiEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.screen != null) {
            return;
        }
        var current = kind;
        var value = payload;
        if (current == null || value == null || client.font == null) return;
        if (current == Kind.MANAGEMENT) renderManagement(event.getGuiGraphics(), (ManagementStatusPayload) value);
        else if (current == Kind.BENCHMARK) renderBenchmark(event.getGuiGraphics(), (BenchmarkInfoPayload) value);
        else renderDictionary(event.getGuiGraphics(), (DictionaryStatusPayload) value);
    }

    static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.player == null) {
            clear();
            MeasuredLatencyClientState.clear();
        }
    }

    static void clear() {
        kind = null;
        payload = null;
    }

    static String selected() {
        var current = kind;
        if (current == null) return "off";
        return switch (current) {
            case MANAGEMENT -> "status";
            case BENCHMARK -> "benchmark";
            case DICTIONARY -> "dictionary";
        };
    }

    private static void renderManagement(GuiGraphics g, ManagementStatusPayload p) {
        int y = 8;
        g.drawString(Minecraft.getInstance().font, Component.translatable("zstdnet.screen.status.title"), 8, y, 0xFFFFFF, true);
        y += 12;
        y = line(g, y, "state", data(Component.translatable("zstdnet.screen.status." + p.state())), "status");
        y = line(g, y, "port", data(Integer.toString(p.port())), "status");
        y = line(g, y, "upload", bytes(p.wireUpBytes()).append(Component.literal(" / ").withStyle(ChatFormatting.GOLD)).append(bytes(p.rawUpBytes())), "status");
        y = line(g, y, "download", bytes(p.wireDownBytes()).append(Component.literal(" / ").withStyle(ChatFormatting.GOLD)).append(bytes(p.rawDownBytes())), "status");
        y = line(g, y, "upload_rate", bytes(p.wireUpRate()).append(Component.literal(" / ").withStyle(ChatFormatting.GOLD)).append(bytes(p.rawUpRate())), "status");
        y = line(g, y, "download_rate", bytes(p.wireDownRate()).append(Component.literal(" / ").withStyle(ChatFormatting.GOLD)).append(bytes(p.rawDownRate())), "status");
        y = line(g, y, "ratio", number(p.ratioPercent(), "%", "%.2f"), "status");
        y = line(g, y, "connections", data(Integer.toString(p.connections())), "status");
        y = line(g, y, "compression_level", data(Integer.toString(p.compressionLevel())), "status");
        y = line(g, y, "latency", number(p.latencyMillis(), " ms", "%.2f"), "status");
        y = line(g, y, "runtime", data(formatDuration(p.uptimeSeconds())), "status");
        y = line(g, y, "benchmark_runs", data(Integer.toString(p.benchmarkRuns())), "status");
        line(g, y, "dictionary", data(p.dictionary()), "status");
    }

    private static void renderBenchmark(GuiGraphics g, BenchmarkInfoPayload p) {
        int y = 8;
        g.drawString(Minecraft.getInstance().font, Component.translatable("zstdnet.screen.benchmark.title"), 8, y, 0xFFFFFF, true);
        y += 12;
        y = line(g, y, "state", data(Component.translatable("zstdnet.screen.benchmark.state." + p.state())), "benchmark");
        y = line(g, y, "mode", data(Component.translatable(p.automatic() ? "zstdnet.screen.benchmark.mode.automatic" : "zstdnet.screen.benchmark.mode.manual")), "benchmark");
        y = line(g, y, "level", data(Integer.toString(p.level())), "benchmark");
        y = line(g, y, "compression", number(p.compressionPercent(), "%", "%.2f"), "benchmark");
        y = line(g, y, "codec", number(p.codecMillis(), " ms", "%.4f"), "benchmark");
        y = line(g, y, "estimated", number(p.estimatedMillis(), " ms", "%.4f"), "benchmark");
        y = line(g, y, "samples", data(Integer.toString(p.samples())), "benchmark");
        y = line(g, y, "interval", number(p.intervalMinutes(), " min", "%.0f"), "benchmark");
        line(g, y, "completed", data(p.completedAt() == 0 ? "-" : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(p.completedAt()))), "benchmark");
    }

    private static void renderDictionary(GuiGraphics g, DictionaryStatusPayload p) {
        int y = 8;
        g.drawString(Minecraft.getInstance().font, Component.translatable("zstdnet.screen.dictionary_status.title"), 8, y, 0xFFFFFF, true);
        y += 12;
        y = line(g, y, "mode", data(Component.translatable("zstdnet.screen.dictionary_status." + p.mode())), "dictionary_status");
        y = line(g, y, "description", data(p.description()), "dictionary_status");
        y = line(g, y, "path", data(p.selectedPath()), "dictionary_status");
        y = line(g, y, "training", data(p.trainingState()), "dictionary_status");
        y = line(g, y, "samples", data(Integer.toString(p.sampleCount())), "dictionary_status");
        y = line(g, y, "bytes", data(Integer.toString(p.sampleBytes())).append(Component.literal(" B").withStyle(ChatFormatting.GREEN)), "dictionary_status");
        line(g, y, "remaining", data(Long.toString(p.remainingSeconds())).append(Component.literal(" s").withStyle(ChatFormatting.GREEN)), "dictionary_status");
    }

    private static int line(GuiGraphics g, int y, String key, Component value, String screen) {
        g.drawString(Minecraft.getInstance().font,
                Component.translatable("zstdnet.screen." + screen + "." + key, value)
                        .withStyle(ChatFormatting.GOLD), 8, y, 0xFFFFFF, true);
        return y + 10;
    }

    private static MutableComponent bytes(long value) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double number = value;
        int unit = 0;
        while (number >= 1024 && unit < units.length - 1) {
            number /= 1024;
            unit++;
        }
        String amount = unit == 0 ? Long.toString(value) : String.format(Locale.ROOT, "%.2f", number);
        return data(amount).append(Component.literal(" " + units[unit]).withStyle(ChatFormatting.GREEN));
    }

    private static MutableComponent number(double value, String unit, String format) {
        if (!Double.isFinite(value)) return data("-");
        return data(String.format(Locale.ROOT, format, value)).append(Component.literal(unit).withStyle(ChatFormatting.GREEN));
    }

    private static MutableComponent data(String value) {
        return Component.literal(value).withStyle(ChatFormatting.AQUA);
    }

    private static MutableComponent data(Component value) {
        return value.copy().withStyle(ChatFormatting.AQUA);
    }

    private static String formatDuration(long seconds) {
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remaining = seconds % 60L;
        if (days > 0L) return String.format(Locale.ROOT, "%dd %02dh %02dm %02ds", days, hours, minutes, remaining);
        return String.format(Locale.ROOT, "%02dh %02dm %02ds", hours, minutes, remaining);
    }

    private enum Kind {MANAGEMENT, BENCHMARK, DICTIONARY}
}
