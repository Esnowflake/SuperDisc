package dev.superdisc.client.mixin;

import com.mojang.blaze3d.audio.Channel;
import dev.superdisc.client.DiscSound;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public abstract class ChannelMixin {
    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void superDiscBind(AudioStream stream, CallbackInfo ci) {
        if (stream instanceof DiscSound.BoundStream bound) bound.bind((Channel)(Object)this);
    }
}
