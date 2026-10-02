package mys.zstdnet.reborn.neoforge.client;

import mys.zstdnet.reborn.neoforge.network.DictionaryStatusPayload;

import mys.zstdnet.reborn.neoforge.network.DictionaryStatusPayload;
import net.minecraft.client.Minecraft;

public final class DictionaryStatusClientState {
    private DictionaryStatusClientState() {
    }

    public static void receive(DictionaryStatusPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> ZstdInfoOverlay.dictionary(payload));
    }
}
