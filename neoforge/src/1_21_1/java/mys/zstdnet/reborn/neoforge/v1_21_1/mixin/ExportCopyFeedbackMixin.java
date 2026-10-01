package mys.zstdnet.reborn.neoforge.v1_21_1.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
abstract class ExportCopyFeedbackMixin {
    @Inject(method = "handleComponentClicked", at = @At("RETURN"))
    private void zstdnet$confirmCopy(Style style, CallbackInfoReturnable<Boolean> result) {
        if (style == null || !Boolean.TRUE.equals(result.getReturnValue())
            || !"zstdnet:dictionary-export".equals(style.getInsertion())) return;
        ClickEvent click = style.getClickEvent();
        if (click != null && click.getAction() == ClickEvent.Action.COPY_TO_CLIPBOARD) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("zstdnet.command.dictionary.copied"), false);
        }
    }
}
