package mys.zstdnet.reborn.neoforge.client;

import mys.zstdnet.reborn.neoforge.network.ManagementStatusPayload;

import mys.zstdnet.reborn.neoforge.network.ManagementStatusPayload;
import net.minecraft.client.Minecraft;

public final class ManagementStatusClientState {
    private ManagementStatusClientState() {
    }

    public static void receive(ManagementStatusPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> ZstdInfoOverlay.management(payload));
    }
}
