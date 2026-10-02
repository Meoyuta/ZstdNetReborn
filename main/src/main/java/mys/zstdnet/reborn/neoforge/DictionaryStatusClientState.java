package mys.zstdnet.reborn.neoforge;

import net.minecraft.client.Minecraft;

final class DictionaryStatusClientState {
    private DictionaryStatusClientState() {
    }

    static void receive(DictionaryStatusPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> ZstdInfoOverlay.dictionary(payload));
    }
}
