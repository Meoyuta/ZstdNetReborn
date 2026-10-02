package mys.zstdnet.reborn.neoforge.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record InfoOverlaySelectionPayload(String selection) implements CustomPacketPayload {
    public static final Type<InfoOverlaySelectionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "info_overlay_selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InfoOverlaySelectionPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, InfoOverlaySelectionPayload::selection,
                    InfoOverlaySelectionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
