package mys.zstdnet.reborn.neoforge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record DebugPongPayload(long nonce) implements CustomPacketPayload {
    static final Type<DebugPongPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "debug_pong"));
    static final StreamCodec<RegistryFriendlyByteBuf, DebugPongPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> buffer.writeLong(payload.nonce()),
                    buffer -> new DebugPongPayload(buffer.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
