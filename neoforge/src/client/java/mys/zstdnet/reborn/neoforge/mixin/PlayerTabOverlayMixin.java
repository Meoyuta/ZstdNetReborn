package mys.zstdnet.reborn.neoforge.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import mys.zstdnet.reborn.neoforge.MeasuredLatencyClientState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void zstdnet$appendLatency(PlayerInfo playerInfo, CallbackInfoReturnable<Component> cir) {
        Object profile = null;
        try {
            profile = playerInfo.getClass().getMethod("getProfile").invoke(playerInfo);
        } catch (ReflectiveOperationException ignored) {
        }
        var profileId = MeasuredLatencyClientState.profileId(profile);
        var measured = profileId == null ? Double.NaN : MeasuredLatencyClientState.get(profileId);
        var text = Double.isFinite(measured)
                ? String.format(java.util.Locale.ROOT, "%.2fms", measured)
                : "--";
        cir.setReturnValue(cir.getReturnValue().copy().append(
                Component.literal(" [" + text + "]").withStyle(ChatFormatting.GRAY)));
    }
}
