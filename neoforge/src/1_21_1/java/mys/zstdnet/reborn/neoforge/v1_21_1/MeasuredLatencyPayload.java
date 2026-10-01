package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

record MeasuredLatencyPayload(UUID playerId, double millis) implements CustomPacketPayload {
    static final Type<MeasuredLatencyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "measured_latency"));
    static final StreamCodec<RegistryFriendlyByteBuf, MeasuredLatencyPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                        buffer.writeUUID(payload.playerId());
                        buffer.writeDouble(payload.millis());
                    },
                    buffer -> new MeasuredLatencyPayload(buffer.readUUID(), buffer.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
