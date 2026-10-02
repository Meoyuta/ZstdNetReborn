package mys.zstdnet.reborn.neoforge.mixin;

import mys.zstdnet.reborn.client.ZstdNetConnectionHooks;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.crypto.Cipher;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @Shadow
    private Channel channel;

    @Inject(method = "configurePacketHandler", at = @At("TAIL"))
    private void zstdnet$installPipeline(ChannelPipeline pipeline, CallbackInfo ci) {
        ZstdNetConnectionHooks.install(pipeline);
    }

    @Inject(method = "setEncryptionKey", at = @At("TAIL"))
    private void zstdnet$repositionAfterEncryption(Cipher decryptCipher, Cipher encryptCipher, CallbackInfo ci) {
        if (channel != null) {
            ZstdNetConnectionHooks.reposition(channel.pipeline());
        }
    }
}
