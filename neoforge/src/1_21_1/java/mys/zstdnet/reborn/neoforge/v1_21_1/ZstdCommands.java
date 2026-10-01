package mys.zstdnet.reborn.neoforge.v1_21_1;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ZstdCommands {
    private static final Map<ZstdNet, Set<String>> ANNOUNCED = new WeakHashMap<>();
    private static final Map<java.util.UUID, java.util.function.LongConsumer> DEBUG_CALLBACKS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ZstdNet mod;

    private ZstdCommands(ZstdNet mod) {
        this.mod = mod;
    }

    static void prompt(ServerPlayer player, ZstdNet mod) {
        if (!player.createCommandSourceStack().hasPermission(2)) return;
        for (String file : mod.dictionaryStore().pendingNames()) {
            player.sendSystemMessage(namingPrompt(file));
        }
    }

    private static Component namingPrompt(String file) {
        return text(file.startsWith("temp_") ? "dictionary.name_temp" : "dictionary.name_prompt", file)
                .copy().withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.SUGGEST_COMMAND,
                        "/zstdnet dictionary name %s ".formatted(file))));
    }

    static void tick(MinecraftServer server, ZstdNet mod) {
        if (!server.isDedicatedServer() || server.getTickCount() % 20 != 0) return;
        try {
            for (String file : mod.dictionaryStore().expireNames(System.currentTimeMillis())) {
                server.createCommandSourceStack().sendSuccess(() -> text("dictionary.auto_named", file), true);
            }
            var announced = ANNOUNCED.computeIfAbsent(mod, ignored -> new HashSet<>());
            var pending = mod.dictionaryStore().pendingNames();
            announced.retainAll(pending);
            for (String file : pending) {
                if (announced.add(file)) {
                    server.createCommandSourceStack().sendSuccess(() -> namingPrompt(file), false);
                    for (var player : server.getPlayerList().getPlayers()) {
                        if (player.createCommandSourceStack().hasPermission(2))
                            player.sendSystemMessage(namingPrompt(file));
                    }
                }
            }
        } catch (IOException e) {
            mod.commandFailed("dictionary naming", e);
        }
    }

    static void register(RegisterCommandsEvent event, ZstdNet mod) {
        ZstdCommands handler = new ZstdCommands(mod);
        var root = Commands.literal("zstdnet");
        registerManagementCommands(root, handler);
        root.then(benchmarkCommand(handler));
        root.then(compressionLevelCommand(handler));
        root.then(createDictionaryCommand(handler, mod));
        event.getDispatcher().register(root);
    }

    private static void registerManagementCommands(
            LiteralArgumentBuilder<CommandSourceStack> root,
            ZstdCommands handler
    ) {
        root.then(managementCommand("start", handler));
        root.then(managementCommand("stop", handler));
        root.then(managementCommand("reload", handler));
        root.then(Commands.literal("ping")
                .executes(context -> handler.ping(context.getSource())));
        root.then(Commands.literal("debug")
                .executes(context -> handler.debug(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> managementCommand(
            String action,
            ZstdCommands handler
    ) {
        return Commands.literal(action)
                .requires(source -> source.hasPermission(2))
                .executes(context -> handler.management(context.getSource(), action));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> benchmarkCommand(ZstdCommands handler) {
        var benchmark = Commands.literal("benchmark");
        benchmark.then(Commands.literal("start")
                .requires(source -> source.hasPermission(2))
            .executes(context -> handler.startBenchmark(context.getSource())));
        benchmark.then(Commands.literal("interval")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
                .executes(context -> handler.setBenchmarkInterval(
                    context.getSource(), IntegerArgumentType.getInteger(context, "minutes")))));
        return benchmark;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> compressionLevelCommand(ZstdCommands handler) {
        return Commands.literal("complevel")
                .requires(source -> source.hasPermission(2))
            .then(Commands.literal("set")
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 22))
                    .executes(context -> handler.setCompressionLevel(
                        context.getSource(), IntegerArgumentType.getInteger(context, "level")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createDictionaryCommand(
            ZstdCommands handler,
            ZstdNet mod
    ) {
        var dictionary = Commands.literal("dictionary");
        dictionary.then(dictionaryAction("stop", handler));
        dictionary.then(dictionaryAction("cancel", handler));
        dictionary.then(dictionaryAction("export", handler));
        dictionary.then(dictionaryAction("unload", handler));
        dictionary.then(trainingCommand(handler));
        dictionary.then(importCommand(handler));
        dictionary.then(switchCommand(handler, mod));
        dictionary.then(nameCommand(handler, mod));
        return dictionary;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dictionaryAction(
            String action,
            ZstdCommands handler
    ) {
        return Commands.literal(action)
                .requires(source -> source.hasPermission(2))
                .executes(context -> handler.dictionary(context.getSource(), action, ""));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> trainingCommand(ZstdCommands handler) {
        var train = Commands.literal("train")
                .requires(source -> source.hasPermission(2))
                .executes(context -> handler.dictionary(context.getSource(), "train", "600"));

        train.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                .executes(context -> handler.dictionary(
                        context.getSource(),
                        "train",
                        Integer.toString(IntegerArgumentType.getInteger(context, "seconds"))
                )));
        return train;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> importCommand(ZstdCommands handler) {
        return Commands.literal("import")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(context -> handler.dictionary(
                                context.getSource(),
                                "import",
                                StringArgumentType.getString(context, "path")
                        )));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> switchCommand(
            ZstdCommands handler,
            ZstdNet mod
    ) {
        var changeDictionary = Commands.literal("switch")
                .requires(source -> source.hasPermission(2));

        changeDictionary.then(Commands.argument("path", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    try {
                        for (String path : mod.dictionaryStore().available()) {
                            if (path.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(path);
                            }
                        }
                    } catch (java.io.IOException e) {
                        mod.commandFailed("dictionary suggestions", e);
                    }
                    return builder.buildFuture();
                })
                .executes(context -> handler.dictionary(
                        context.getSource(),
                        "switch",
                        StringArgumentType.getString(context, "path")
                )));
        return changeDictionary;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> nameCommand(
            ZstdCommands handler,
            ZstdNet mod
    ) {
        var nameDictionary = Commands.literal("name")
                .requires(source -> source.hasPermission(2));

        nameDictionary.then(Commands.argument("file", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (String file : mod.dictionaryStore().pendingNames()) {
                        if (file.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(file);
                        }
                    }
                    return builder.buildFuture();
                })
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> handler.nameDictionary(
                                context.getSource(),
                                StringArgumentType.getString(context, "file"),
                                StringArgumentType.getString(context, "name")
                        ))));
        return nameDictionary;
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("zstdnet.command." + key, arguments);
    }

    private static void success(CommandSourceStack source, String key, Object... arguments) {
        source.sendSuccess(() -> text(key, arguments), false);
    }

    private static int fail(CommandSourceStack source, String key) {
        source.sendFailure(text(key));
        return 0;
    }

    private int startBenchmark(CommandSourceStack source) {
        if (!source.getServer().isDedicatedServer()) return fail(source, "benchmark.dedicated_only");
        var player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (!mod.startBenchmark(player)) return fail(source, "benchmark.not_started");
        success(source, "benchmark.started");
        return 1;
    }

    private int setBenchmarkInterval(CommandSourceStack source, int minutes) {
        try {
            mod.setBenchmarkInterval(minutes);
            success(source, "benchmark.interval_set", minutes);
            return 1;
        } catch (IOException | IllegalStateException e) {
            mod.commandFailed("set benchmark interval", e);
            return fail(source, "benchmark.interval_failed");
        }
    }

    private int setCompressionLevel(CommandSourceStack source, int level) {
        try {
            mod.setTemporaryCompressionLevel(level);
            success(source, "complevel.set", level);
            return 1;
        } catch (IllegalStateException e) {
            mod.commandFailed("set compression level", e);
            return fail(source, "complevel.failed");
        }
    }

    private int ping(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        mod.measureDirectLatency(player, millis -> player.sendSystemMessage(
                text("ping.direct", String.format(Locale.ROOT, "%.2f", millis))));
        return 1;
    }

    private int debug(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        try {
            var report = mod.writeDebugReport(player);
            source.sendSuccess(() -> text("debug.file", report.toString()), false);
            return 1;
        } catch (IOException error) {
            mod.commandFailed("debug report", error);
            source.sendFailure(text("debug.failed"));
            return 0;
        }
    }

    private static String formatBytes(long bytes) {
        var units = new String[]{"B", "KiB", "MiB", "GiB", "TiB"};
        var value = (double) bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? "%d %s".formatted(bytes, units[unit])
                : String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private int management(CommandSourceStack source, String action) {
        if (!source.getServer().isDedicatedServer()) {
            return fail(source, "management.dedicated_only");
        }
        try {
            switch (action) {
                case "start" -> {
                    if (!mod.start(source.getServer())) return fail(source, "management.init_failed");
                    success(source, "management.started", source.getServer().getPort());
                }
                case "stop" -> {
                    mod.stop();
                    success(source, "management.stopped");
                }
                case "reload" -> {
                    if (!mod.reload(source.getServer())) return fail(source, "management.init_failed");
                    success(source, mod.dictionaryStore().dictionary() == null
                            ? "management.reloaded_without_dictionary" : "management.reloaded");
                }
                default -> fail(source, "management.failed");
            }
            return 1;
        } catch (Exception e) {
            mod.commandFailed(action, e);
            return fail(source, "management.failed");
        }
    }

    private int dictionary(CommandSourceStack source, String action, String argument) {
        var store = mod.dictionaryStore();
        var trainer = mod.dictionaryTrainer();
        if (store == null || trainer == null) return fail(source, "dictionary.service_unavailable");
        try {
            return switch (action) {
                case "unload" -> unloadDictionary(source, store, trainer);
                case "switch" -> switchDictionary(source, argument, store, trainer);
                case "train" -> trainDictionary(source, argument, trainer);
                case "stop" -> stopTraining(source, trainer);
                case "cancel" -> cancelTraining(source, trainer);
                case "import" -> importDictionary(source, argument, store, trainer);
                case "export" -> exportDictionary(source, store);
                default -> fail(source, "dictionary.failed");
            };
        } catch (Exception e) {
            mod.commandFailed("dictionary %s".formatted(action), e);
            return fail(source, "dictionary.failed");
        }
    }

    private int unloadDictionary(
            CommandSourceStack source,
            ZstdDictionaryStore store,
            ZstdDictionaryTrainer trainer
    ) throws IOException {
        trainer.abort();
        store.unload();
        success(source, "dictionary.unloaded");
        return 1;
    }

    private int switchDictionary(
            CommandSourceStack source,
            String argument,
            ZstdDictionaryStore store,
            ZstdDictionaryTrainer trainer
    ) throws IOException {
        var path = unquotePath(argument.trim());
        if (path.isBlank()) return fail(source, "dictionary.invalid_path");

        trainer.abort();
        mod.disconnectForDictionarySwitch();
        var selected = store.select(Path.of(path));
        if (!(source.getEntity() instanceof ServerPlayer)) {
            success(source, "dictionary.switched", store.selectedPath(), Long.toUnsignedString(selected.id()));
        }
        return 1;
    }

    private int trainDictionary(
            CommandSourceStack source,
            String seconds,
            ZstdDictionaryTrainer trainer
    ) {
        if (!mod.isRunning()) return fail(source, "dictionary.compression_stopped");
        if (!trainer.start(Duration.ofSeconds(Integer.parseInt(seconds)), 9)) {
            return fail(source, "dictionary.busy");
        }
        success(source, "dictionary.collecting", seconds);
        return 1;
    }

    private int stopTraining(CommandSourceStack source, ZstdDictionaryTrainer trainer) {
        if (!trainer.stopAndFinalize()) return fail(source, "dictionary.not_collecting");
        success(source, "dictionary.queued");
        return 1;
    }

    private int cancelTraining(CommandSourceStack source, ZstdDictionaryTrainer trainer) {
        trainer.abort();
        success(source, "dictionary.cancelled");
        return 1;
    }

    private int importDictionary(
            CommandSourceStack source,
            String argument,
            ZstdDictionaryStore store,
            ZstdDictionaryTrainer trainer
    ) throws IOException {
        var path = unquotePath(argument.trim()).trim();
        if (path.isEmpty()) return fail(source, "dictionary.invalid_path");

        var file = Path.of(path);
        if (!file.isAbsolute()) file = store.dictionaryPath().getParent().resolve(file);
        trainer.abort();
        var dictionary = store.importFrom(file);
        success(source, "dictionary.saved_pending", Long.toUnsignedString(dictionary.id()));
        return 1;
    }

    private int exportDictionary(
            CommandSourceStack source,
            ZstdDictionaryStore store
    ) throws IOException {
        if (store.dictionary() == null) return fail(source, "dictionary.no_dictionary");

        var path = store.export().toString();
        success(source, "dictionary.exported");
        source.sendSuccess(() -> exportPath(path), false);
        return 1;
    }

    private Component exportPath(String path) {
        return text("dictionary.export_path", path).copy().withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withInsertion("zstdnet:dictionary-export")
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        text("dictionary.copy_path_hover", path)))
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path)));
    }

    private static String unquotePath(String path) {
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }

    private int nameDictionary(CommandSourceStack source, String file, String name) {
        try {
            var named = mod.dictionaryStore().name(file, name);
            success(source, "dictionary.named", named.getFileName().toString());
            return 1;
        } catch (IOException e) {
            mod.commandFailed("dictionary name", e);
            return fail(source, "dictionary.name_failed");
        }
    }
}
