package Skin.Management.skin_management.client.mixin;

import Skin.Management.skin_management.client.SkinManagerClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {

    @Inject(
            method = "getSkinTexture()Lnet/minecraft/util/Identifier;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void skin_management$overrideSkinTexture(CallbackInfoReturnable<Identifier> cir) {
        Identifier id = SkinManagerClient.getOrFetch((AbstractClientPlayerEntity)(Object)this);
        if (id != null) cir.setReturnValue(id);
    }
}
