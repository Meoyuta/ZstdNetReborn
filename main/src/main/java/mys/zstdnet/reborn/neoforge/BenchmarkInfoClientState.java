package mys.zstdnet.reborn.neoforge;

import net.minecraft.client.Minecraft;

final class BenchmarkInfoClientState {
    private static volatile BenchmarkInfoPayload latest;

    private BenchmarkInfoClientState() {
    }

    static void receive(BenchmarkInfoPayload payload) {
        latest = payload;
        var client = Minecraft.getInstance();
        client.execute(() -> ZstdInfoOverlay.benchmark(payload));
    }

    static BenchmarkInfoPayload latest() {
        return latest;
    }
}
