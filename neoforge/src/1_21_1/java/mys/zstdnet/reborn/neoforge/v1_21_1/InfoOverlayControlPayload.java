package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record InfoOverlayControlPayload(boolean open) implements CustomPacketPayload {
    static final Type<InfoOverlayControlPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "info_overlay_control"));
    static final StreamCodec<RegistryFriendlyByteBuf, InfoOverlayControlPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> buffer.writeBoolean(payload.open),
                    buffer -> new InfoOverlayControlPayload(buffer.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
