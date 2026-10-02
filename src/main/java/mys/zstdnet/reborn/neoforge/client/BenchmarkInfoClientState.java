package mys.zstdnet.reborn.neoforge.client;

import mys.zstdnet.reborn.neoforge.network.BenchmarkInfoPayload;

import mys.zstdnet.reborn.neoforge.network.BenchmarkInfoPayload;
import mys.zstdnet.reborn.neoforge.network.BenchmarkInfoPayload;
import net.minecraft.client.Minecraft;

public final class BenchmarkInfoClientState {
    private static volatile BenchmarkInfoPayload latest;

    private BenchmarkInfoClientState() {
    }

    public static void receive(BenchmarkInfoPayload payload) {
        latest = payload;
        var client = Minecraft.getInstance();
        client.execute(() -> ZstdInfoOverlay.benchmark(payload));
    }

    static BenchmarkInfoPayload latest() {
        return latest;
    }
}
