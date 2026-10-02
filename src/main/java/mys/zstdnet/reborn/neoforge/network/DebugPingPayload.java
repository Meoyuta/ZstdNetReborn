package mys.zstdnet.reborn.neoforge.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DebugPingPayload(long nonce) implements CustomPacketPayload {
    public static final Type<DebugPingPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "debug_ping"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugPingPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> buffer.writeLong(payload.nonce()),
                    buffer -> new DebugPingPayload(buffer.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
