package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

final class InfoOverlaySelectionScreen extends Screen {
    private static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.zstdnet.overlay_selector", GLFW.GLFW_KEY_F8, "key.categories.misc");

    InfoOverlaySelectionScreen() {
        super(Component.translatable("zstdnet.screen.overlay_selector.title"));
    }

    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEY);
    }

    static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_KEY.consumeClick()) {
            var client = Minecraft.getInstance();
            if (client.player != null && !(client.screen instanceof InfoOverlaySelectionScreen)) {
                client.setScreen(new InfoOverlaySelectionScreen());
            }
        }
    }

    @Override
    protected void init() {
        int width = 220;
        int left = (this.width - width) / 2;
        int top = this.height / 2 - 54;
        String selected = ZstdInfoOverlay.selected();
        addRenderableWidget(Button.builder(option("status", selected), button -> choose("status", selected))
                .bounds(left, top, width, 20).build());
        addRenderableWidget(Button.builder(option("benchmark", selected), button -> choose("benchmark", selected))
                .bounds(left, top + 24, width, 20).build());
        addRenderableWidget(Button.builder(option("dictionary", selected), button -> choose("dictionary", selected))
                .bounds(left, top + 48, width, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("zstdnet.screen.overlay_selector.off"),
                        button -> choose("off", selected))
                .bounds(left, top + 76, width, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left, top + 104, width, 20).build());
    }

    private static Component option(String name, String selected) {
        var label = Component.translatable("zstdnet.screen.overlay_selector." + name);
        return selected.equals(name)
                ? Component.translatable("zstdnet.screen.overlay_selector.selected", label)
                : label;
    }

    private void choose(String selection, String selected) {
        var requested = selection.equals(selected) ? "off" : selection;
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(new ServerboundCustomPayloadPacket(new InfoOverlaySelectionPayload(requested)));
        }
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
