package mys.zstdnet.reborn.neoforge.mixin;

import mys.zstdnet.reborn.neoforge.ZstdNetConnectHooks;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
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
