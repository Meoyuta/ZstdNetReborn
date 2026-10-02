package mys.zstdnet.reborn.neoforge;

import net.minecraft.client.Minecraft;

final class ManagementStatusClientState {
    private ManagementStatusClientState() {
    }

    static void receive(ManagementStatusPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> ZstdInfoOverlay.management(payload));
    }
}
