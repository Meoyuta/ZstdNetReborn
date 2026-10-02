package mys.zstdnet.reborn.neoforge.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record MeasuredLatencyPayload(UUID playerId, double millis) implements CustomPacketPayload {
    public static final Type<MeasuredLatencyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "measured_latency"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MeasuredLatencyPayload> STREAM_CODEC =
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
