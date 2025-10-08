package Skin.Management.skin_management.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

public final class SkinManagerClient {
    private static final String MODID = "skin_management";

    private static final Map<UUID, Identifier> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SLIM = new ConcurrentHashMap<>();
    private static final Set<UUID> INFLIGHT = ConcurrentHashMap.newKeySet();

    private static final Map<UUID, Long> LAST_CHECK = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SELECT_URL = new ConcurrentHashMap<>();

    private static final Map<UUID, Boolean> PREFERRED_SLIM = new ConcurrentHashMap<>();

    private static volatile long REFRESH_INTERVAL_MS = 5_000L;
    public static void setRefreshIntervalMs(long ms) { REFRESH_INTERVAL_MS = Math.max(1_000L, ms); }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SkinManagerClient");
        t.setDaemon(true);
        return t;
    });

    private SkinManagerClient() {}

    private static Identifier idFor(UUID u) {
        return new Identifier(MODID, "remote/" + u.toString().replace("-", ""));
    }

    public static Identifier getOrFetch(AbstractClientPlayerEntity player) {
        if (player == null) return null;
        UUID u = player.getUuid();
        Identifier id = CACHE.get(u);
        if (id == null) {
            fetchAndApplyFor(u);
            return null;
        }
        if (shouldPoll(u)) fetchAndApplyFor(u);
        return id;
    }

    public static Identifier getOrFetch(UUID uuid) {
        if (uuid == null) return null;
        Identifier id = CACHE.get(uuid);
        if (id != null) return id;
        fetchAndApplyFor(uuid);
        return null;
    }

    public static Identifier getCached(UUID uuid) {
        return uuid == null ? null : CACHE.get(uuid);
    }

    public static Boolean isSlimOrNull(UUID uuid) {
        if (uuid == null) return null;
        Boolean v = SLIM.get(uuid);
        if (v != null) return v;
        Boolean pref = PREFERRED_SLIM.get(uuid);
        if (pref != null) return pref;
        return null;
    }

    public static boolean isSlim(UUID uuid, boolean defVal) {
        Boolean v = SLIM.get(uuid);
        if (v != null) return v;
        Boolean pref = PREFERRED_SLIM.get(uuid);
        if (pref != null) return pref;
        return defVal;
    }

    public static void setSlim(UUID uuid, boolean slim) {
        if (uuid != null) {
            SLIM.put(uuid, slim);
            PREFERRED_SLIM.put(uuid, slim);
        }
    }

    public static void ensureFetch(UUID uuid) {
        if (uuid == null) return;
        if (!CACHE.containsKey(uuid) || shouldPoll(uuid)) {
            fetchAndApplyFor(uuid);
        }
    }

    public static void forceFetch(UUID uuid) {
        if (uuid == null) return;
        LAST_CHECK.remove(uuid);  
        fetchAndApplyFor(uuid);     
    }

    private static boolean shouldPoll(UUID uuid) {
        long now = System.currentTimeMillis();
        long last = LAST_CHECK.getOrDefault(uuid, 0L);
        if (now - last >= REFRESH_INTERVAL_MS) {
            LAST_CHECK.put(uuid, now);
            return true;
        }
        return false;
    }

    public static void refresh(UUID uuid) {
        TextureManager tm = MinecraftClient.getInstance().getTextureManager();
        Identifier id = CACHE.remove(uuid);
        if (id != null) tm.destroyTexture(id);
        SELECT_URL.remove(uuid); 
        fetchAndApplyFor(uuid);
    }

    public static void fetchAndApplyFor(UUID uuid) {
        if (uuid == null) return;
        if (!INFLIGHT.add(uuid)) return;

        CompletableFuture<ServerApiClient.SelectedSkin> selected =
                ServerApiClient.fetchSelectedAsync(uuid);

        selected.thenCompose(sel -> {
            if (sel == null) return CompletableFuture.completedFuture(null);
            SLIM.put(uuid, sel.slim());

            String prev = SELECT_URL.get(uuid);
            if (prev != null && prev.equals(sel.url())) {
                return CompletableFuture.completedFuture(null);
            }
            SELECT_URL.put(uuid, sel.url());
            return ServerApiClient.downloadTextureAsync(sel.url());

        }).whenCompleteAsync((tex, err) -> {
            INFLIGHT.remove(uuid);
            if (err != null || tex == null) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                Identifier id = idFor(uuid);
                TextureManager tm = mc.getTextureManager();
                tm.destroyTexture(id);        // ล้างเก่าก่อนเสมอ
                tm.registerTexture(id, tex);  // ใส่ใหม่ด้วย id เดิม
                CACHE.put(uuid, id);
            });
        }, EXEC);
    }

    public static void clearAll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            TextureManager tm = mc.getTextureManager();
            for (Identifier id : CACHE.values()) tm.destroyTexture(id);
            CACHE.clear();
            SLIM.clear();
            INFLIGHT.clear();
            LAST_CHECK.clear();
            SELECT_URL.clear();
            PREFERRED_SLIM.clear();
        });
    }
}
