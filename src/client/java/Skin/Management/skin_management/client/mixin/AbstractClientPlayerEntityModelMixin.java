package Skin.Management.skin_management.client.mixin;

import Skin.Management.skin_management.client.SkinManagerClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractClientPlayerEntity.class, priority = 2000)
public abstract class AbstractClientPlayerEntityModelMixin {

    @Inject(
            method = "getModel()Ljava/lang/String;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void skin_management$overrideModel(CallbackInfoReturnable<String> cir) {
        AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;

        Boolean slim = SkinManagerClient.isSlimOrNull(self.getUuid());
        if (slim != null) {
            cir.setReturnValue(slim ? "slim" : "default");
        }
    }
}
