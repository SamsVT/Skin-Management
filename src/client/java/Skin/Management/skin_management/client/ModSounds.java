package Skin.Management.skin_management.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {

    public static final Identifier ID_UI_UPLOAD   = new Identifier(SkinManagementClient.MODID, "ui.upload");
    public static final Identifier ID_UI_ERROR    = new Identifier(SkinManagementClient.MODID, "ui.error");
    public static final Identifier ID_UI_COMPLETE = new Identifier(SkinManagementClient.MODID, "ui.complete");

    public static final SoundEvent UI_UPLOAD   = SoundEvent.of(ID_UI_UPLOAD);
    public static final SoundEvent UI_ERROR    = SoundEvent.of(ID_UI_ERROR);
    public static final SoundEvent UI_COMPLETE = SoundEvent.of(ID_UI_COMPLETE);

    public static void register() {
        Registry.register(Registries.SOUND_EVENT, ID_UI_UPLOAD,   UI_UPLOAD);
        Registry.register(Registries.SOUND_EVENT, ID_UI_ERROR,    UI_ERROR);
        Registry.register(Registries.SOUND_EVENT, ID_UI_COMPLETE, UI_COMPLETE);
    }

    public static void play(SoundEvent event) {
        try {
            MinecraftClient.getInstance().getSoundManager()
                    .play(PositionedSoundInstance.master(event, 1.0f));
        } catch (Exception ignored) {}
    }

    private ModSounds() {}
}
