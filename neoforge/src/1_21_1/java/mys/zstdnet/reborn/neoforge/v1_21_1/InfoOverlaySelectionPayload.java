package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record InfoOverlaySelectionPayload(String selection) implements CustomPacketPayload {
    static final Type<InfoOverlaySelectionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "info_overlay_selection"));
    static final StreamCodec<RegistryFriendlyByteBuf, InfoOverlaySelectionPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, InfoOverlaySelectionPayload::selection,
                    InfoOverlaySelectionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
