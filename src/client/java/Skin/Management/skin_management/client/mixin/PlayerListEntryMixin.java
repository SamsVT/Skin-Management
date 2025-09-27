package Skin.Management.skin_management.client.mixin;

import Skin.Management.skin_management.client.SkinManagerClient;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = PlayerListEntry.class, priority = 1)
public abstract class PlayerListEntryMixin {

    private UUID sm$getUuid() {
        PlayerListEntry self = (PlayerListEntry)(Object)this;
        GameProfile gp = self.getProfile();
        return gp != null ? gp.getId() : null;
    }

    @Inject(method = "getSkinTexture()Lnet/minecraft/util/Identifier;", at = @At("HEAD"), cancellable = true)
    private void sm$overrideSkinTexture(CallbackInfoReturnable<Identifier> cir) {
        UUID uuid = sm$getUuid();
        if (uuid == null) return;

        Identifier cached = SkinManagerClient.getCached(uuid);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        SkinManagerClient.ensureFetch(uuid); // ยังไม่มี -> โหลด async แล้วใช้ของเดิมไปก่อน
    }

    @Inject(method = "getModel()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void sm$overrideModel(CallbackInfoReturnable<String> cir) {
        UUID uuid = sm$getUuid();
        if (uuid == null) return;

        Boolean slim = SkinManagerClient.isSlimOrNull(uuid);
        if (slim != null) {
            cir.setReturnValue(slim ? "slim" : "default");
        }
    }
}