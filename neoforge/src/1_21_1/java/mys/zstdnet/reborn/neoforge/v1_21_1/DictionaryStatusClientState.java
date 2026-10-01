package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.client.Minecraft;

final class DictionaryStatusClientState {
    private DictionaryStatusClientState() {
    }

    static void receive(DictionaryStatusPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> ZstdInfoOverlay.dictionary(payload));
    }
}
