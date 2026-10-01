package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

record BenchmarkInfoPayload(
        String state,
        int level,
        boolean automatic,
        double compressionPercent,
        double codecMillis,
        double estimatedMillis,
        int samples,
        long completedAt,
        int intervalMinutes
) implements CustomPacketPayload {
    static final Type<BenchmarkInfoPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "benchmark_info"));
    static final StreamCodec<RegistryFriendlyByteBuf, BenchmarkInfoPayload> STREAM_CODEC = StreamCodec.of(
            BenchmarkInfoPayload::write,
            BenchmarkInfoPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buffer, BenchmarkInfoPayload payload) {
        buffer.writeUtf(payload.state, 32);
        buffer.writeVarInt(payload.level);
        buffer.writeBoolean(payload.automatic);
        buffer.writeDouble(payload.compressionPercent);
        buffer.writeDouble(payload.codecMillis);
        buffer.writeDouble(payload.estimatedMillis);
        buffer.writeVarInt(payload.samples);
        buffer.writeVarLong(payload.completedAt);
        buffer.writeVarInt(payload.intervalMinutes);
    }

    private static BenchmarkInfoPayload read(RegistryFriendlyByteBuf buffer) {
        return new BenchmarkInfoPayload(
                buffer.readUtf(32),
                buffer.readVarInt(),
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readVarInt(),
                buffer.readVarLong(),
                buffer.readVarInt()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
