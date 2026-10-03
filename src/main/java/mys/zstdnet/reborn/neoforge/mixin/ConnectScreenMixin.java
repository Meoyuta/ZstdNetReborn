package mys.zstdnet.reborn.neoforge.mixin;

import mys.zstdnet.reborn.neoforge.client.ZstdNetConnectHooks;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Shadow
    private void updateStatus(Component component) {
        throw new AssertionError();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "connect", at = @At("HEAD"))
    private void zstdnet$showNegotiating(CallbackInfo ci) {
        updateStatus(Component.translatable("zstdnet.connect.negotiating"));
    }

    @ModifyVariable(
        method = "startConnecting",
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true
    )
    private static ServerAddress zstdnet$interceptConnect(ServerAddress original) {
        return ZstdNetConnectHooks.intercept(original);
    }
}
