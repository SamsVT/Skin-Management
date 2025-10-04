package Skin.Management.skin_management.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class SkinManagementClient implements ClientModInitializer {

    public static final String MODID = "skin_management";

    private static KeyBinding openUiKey;

    // fallback poll
    private static int tickCounter = 0;
    private static final int ENSURE_INTERVAL_TICKS = 20; // ~1s
    private static final int ENSURE_LIMIT_PER_PASS = 16; // จำกัดจำนวนต่อรอบ

    @Override
    public void onInitializeClient() {
        ModSounds.register();
        SkinManagerClient.setRefreshIntervalMs(1_000L); // โพลสำรอง 1s

        openUiKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key." + MODID + ".open_ui",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_K,
                        "key.categories." + MODID
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openUiKey.wasPressed()) {
                openNow();
            }

            // fallback: โพลผู้เล่นในโลกทุก ~1s เผื่อ SSE หลุด
            if (client.world != null) {
                tickCounter++;
                if ((tickCounter % ENSURE_INTERVAL_TICKS) == 0) {
                    int count = 0;
                    for (var p : client.world.getPlayers()) {
                        if (p == null) continue;
                        SkinManagerClient.ensureFetch(p.getUuid());
                        if (++count >= ENSURE_LIMIT_PER_PASS) break;
                    }
                }
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                try {
                    // เริ่มฟัง SSE: เมื่อมีคน upload/select -> บังคับ fetch ทันที
                    ServerApiClient.startSse(evt -> {
                        if (evt != null && evt.uuid != null) {
                            client.execute(() -> {
                                if (evt.slim != null) SkinManagerClient.setSlim(evt.uuid, evt.slim);
                                SkinManagerClient.forceFetch(evt.uuid); // บังคับโหลดทันที
                            });
                        }
                    });

                    if (client.player != null) {
                        SkinManagerClient.fetchAndApplyFor(client.player.getUuid());
                    }

                    var t = SkinUploadScreen.Toasts.connectionTrans("title.skin_cloud", "toast.cloud.checking");
                    ServerApiClient.pingAsyncOk().thenAccept(ok -> {
                        client.execute(() -> t.complete(ok, net.minecraft.text.Text.translatable(ok ? "toast.cloud.connected" : "toast.cloud.failed").getString()));
                    });
                } catch (Exception ignored) {}
            });
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> {
                try { SkinManagerClient.clearAll(); } catch (Exception ignored) {}
                try { ServerApiClient.stopSse(); } catch (Exception ignored) {}
            });
        });

        System.out.println("[" + MODID + "] client initialized, keybind registered.");
    }

    public static void openNow() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.setScreen(new SkinUploadScreen()));
    }
}