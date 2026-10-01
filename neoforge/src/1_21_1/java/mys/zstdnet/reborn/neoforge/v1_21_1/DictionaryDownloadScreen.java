package mys.zstdnet.reborn.neoforge.v1_21_1;

import mys.zstdnet.reborn.core.netty.ZstdDictionaryDownloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** All UI mutations run on the client thread; the connection screen continues ticking. */
final class DictionaryDownloadScreen extends ProgressScreen {
    private final Screen previous;
    private int ticks;
    private boolean finished;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ZstdNet");

    private DictionaryDownloadScreen(Screen previous) {
        super(false);
        this.previous = previous;
        progressStartNoAbort(Component.translatable("zstdnet.screen.dictionary.title"));
        progressStage(Component.translatable("zstdnet.screen.dictionary.receiving"));
    }

    @Override
    public void tick() {
        previous.tick();
        ticks++;
        var client = Minecraft.getInstance();
        if (finished && ticks >= 10 && client.screen == this) client.setScreen(previous);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() {
        // Return to Minecraft's connection screen, which owns its Cancel button.
        Minecraft.getInstance().setScreen(previous);
    }

    static ZstdDictionaryDownloadListener listener() {
        return new ZstdDictionaryDownloadListener() {
            private DictionaryDownloadScreen screen;

            @Override
            public void started(long id, int bytes) {
                LOGGER.info("Receiving server dictionary id={} bytes={}", Long.toUnsignedString(id), bytes);
                Minecraft.getInstance().execute(() -> {
                    var client = Minecraft.getInstance();
                    if (client.player == null && client.screen != null
                        && !(client.screen instanceof DisconnectedScreen)) {
                        screen = new DictionaryDownloadScreen(client.screen);
                        client.setScreen(screen);
                    }
                });
            }

            @Override
            public void progress(int received, int total) {
                Minecraft.getInstance().execute(() -> {
                    if (screen != null) {
                        screen.progressStage(Component.translatable("zstdnet.screen.dictionary.progress", received, total));
                        screen.progressStagePercentage(total == 0 ? 0 : (int) (100L * received / total));
                    }
                });
            }

            @Override
            public void completed(long id) {
                LOGGER.info("Server dictionary validated and applied, id={}; sending ACK", Long.toUnsignedString(id));
                Minecraft.getInstance().execute(() -> {
                    if (screen != null) {
                        screen.finished = true;
                        screen.progressStage(Component.translatable("zstdnet.screen.dictionary.complete"));
                        screen.progressStagePercentage(100);
                    }
                });
            }

            @Override
            public void failed(String message) {
                LOGGER.warn("Server dictionary synchronization failed: {}", message);
                restore();
            }

            private void restore() {
                Minecraft.getInstance().execute(() -> {
                    var client = Minecraft.getInstance();
                    if (screen != null && client.screen == screen) client.setScreen(screen.previous);
                    screen = null;
                });
            }
        };
    }
}
