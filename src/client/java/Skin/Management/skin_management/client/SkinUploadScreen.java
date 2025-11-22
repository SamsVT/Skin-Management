package Skin.Management.skin_management.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class SkinUploadScreen extends Screen {
    private static final float UI_SCALE = 1.00f;
    private static final float TOP_YR   = 0.16f;
    private static final float BOT_YR   = 0.86f;

    private static final int C_BG = 0x33000000;         // dim ทั้งหน้าจอ ~20%
    private static final int C_PANEL = 0x7A000000;
    private static final int C_PANEL_SOFT = 0x52000000;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT_DIM = 0xBBDDDDDD;
    private static final int C_ACCENT = 0xFFFFFFFF;

    private int u(int px){ return Math.max(1, Math.round(px * UI_SCALE)); }
    private static final String MOD_ID = "skin_management";

    private File selectedFile;
    private CheckboxWidget slimToggle;
    private ButtonWidget chooseButton, uploadButton;

    private Identifier previewId;
    private NativeImageBackedTexture previewTex;
    private int previewW, previewH;

    private OtherClientPlayerEntity previewPlayer;
    private boolean previewSlim;

    private int zoneTop, zoneBot;

    private int leftX, leftY, leftW, leftH;
    private int rightX, rightY, rightW, rightH;

    private int dropX, dropY, dropW, dropH;

    private int tabX, tabY, tabW, tabH;
    private int tabItemW, tabGap, tabScroll = 0;

    private int controlsY;

    private final List<HistoryEntry> history = new ArrayList<>();

    public SkinUploadScreen() { super(Text.translatable("screen.skin_management.title")); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    protected void init() {
        zoneTop = Math.round(this.height * TOP_YR);
        zoneBot = Math.round(this.height * BOT_YR);
        if (zoneBot <= zoneTop + u(160)) zoneBot = zoneTop + u(160);

        int pad = u(10);
        int rowH = u(20);

        leftW = clamp((int)(this.width * 0.34f), u(260), u(420));
        leftX = pad;
        leftY = zoneTop;
        leftH = zoneBot - zoneTop;

        rightX = leftX + leftW + pad;
        rightY = zoneTop;
        rightW = this.width - rightX - pad;
        rightH = zoneBot - zoneTop;

        int inset = u(10);

        dropX = rightX + inset;
        dropY = rightY + inset;
        dropW = rightW - inset * 2;
        dropH = rightH - inset * 2;

        tabX = leftX + inset;
        tabY = leftY + inset + u(18);
        tabW = leftW - inset * 2;
        tabH = u(58);
        tabItemW = u(140);
        tabGap = u(6);

        int btnX = leftX + inset;
        int btnW = leftW - inset * 2;
        int gapY = u(7);

        controlsY = tabY + tabH + u(12);

        clearChildren();

        loadHistory();
        tabScroll = clamp(tabScroll, 0, calcTabMaxScroll());

        chooseButton = new TransparentButton(btnX, controlsY, btnW, rowH,
                Text.translatable("screen.skin_management.button.select_png"),
                b -> openHistoryFolder());
        addDrawableChild(chooseButton);

        slimToggle = new CheckboxWidget(btnX, controlsY + rowH + gapY, btnW, rowH,
                Text.translatable("screen.skin_management.slim_model"), false);
        addDrawableChild(slimToggle);

        uploadButton = new TransparentButton(btnX, controlsY + (rowH + gapY) * 2, btnW, rowH,
                Text.translatable("screen.skin_management.button.upload"),
                b -> doUpload());
        uploadButton.active = (selectedFile != null);
        addDrawableChild(uploadButton);

        rebuildPreviewPlayer();
    }

    private int calcTabMaxScroll() {
        int total = history.size() * (tabItemW + tabGap) - tabGap;
        return Math.max(0, total - tabW);
    }

    @Override public void resize(MinecraftClient c, int w, int h){ super.resize(c, w, h); this.init(); }

    @Override
    public void close() {
        disposePreview();
        disposeHistory();
        super.close();
    }

    private void disposePreview() {
        if (previewId != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(previewId);
            previewId = null;
        }
        previewTex = null; previewW = previewH = 0;
        previewPlayer = null;
    }

    private void disposeHistory() {
        for (HistoryEntry e : history) {
            if (e.thumbId != null)
                MinecraftClient.getInstance().getTextureManager().destroyTexture(e.thumbId);
        }
        history.clear();
    }

    private File historyDir() {
        File dir = new File(MinecraftClient.getInstance().runDirectory, "skin_history");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(0, i) : name;
    }

    private File copyToHistory(File src) throws Exception {
        File dir = historyDir();
        String base = stripExt(src.getName());
        File target = new File(dir, base + ".png");
        int n = 2;
        while (target.exists()) {
            target = new File(dir, base + "_" + n + ".png");
            n++;
        }
        Files.copy(src.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    private void loadHistory() {
        disposeHistory();
        File dir = historyDir();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null) return;

        List<File> list = new ArrayList<>();
        for (File f : files) list.add(f);
        list.sort(Comparator.comparingLong(File::lastModified).reversed());

        for (File f : list) {
            try (FileInputStream in = new FileInputStream(f)) {
                NativeImage img = NativeImage.read(in);
                int w = img.getWidth(), h = img.getHeight();

                NativeImage thumb = makeThumb(img, 4096);
                img.close();

                NativeImageBackedTexture tex = new NativeImageBackedTexture(thumb);
                tex.setFilter(false, false);
                Identifier id = new Identifier(MOD_ID, "thumb/" + System.nanoTime());
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);

                HistoryEntry he = new HistoryEntry(f, w, h);
                he.thumbId = id;
                history.add(he);
            } catch (Exception ignored) {}
        }
    }

    private static NativeImage makeThumb(NativeImage src, int size) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        NativeImage out = new NativeImage(size, size, true);

        for (int y = 0; y < size; y++) {
            int sy = (int)((y / (float)size) * sh);
            if (sy >= sh) sy = sh - 1;
            for (int x = 0; x < size; x++) {
                int sx = (int)((x / (float)size) * sw);
                if (sx >= sw) sx = sw - 1;

                int c = src.getColor(sx, sy);
                out.setColor(x, y, c);
            }
        }
        return out;
    }

    private static class HistoryEntry {
        final File file;
        final int w, h;
        Identifier thumbId;
        HistoryEntry(File file, int w, int h) { this.file = file; this.w = w; this.h = h; }
    }

    private void setSelectedFile(File f) { setSelectedFile(f, false); }

    private void setSelectedFile(File f, boolean fromHistory) {
        if (f == null) return;
        String name = f.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png")) {
            toastErrorKey("toast.error.not_png");
            try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
            return;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            NativeImage img = NativeImage.read(in);

            int w = img.getWidth(), h = img.getHeight();
            boolean square = (w == h);
            boolean pow2 = (w & (w - 1)) == 0;
            boolean inRange = (w >= 64 && w <= 4096);
            if (!(square && pow2 && inRange)) {
                img.close();
                toastErrorKey("toast.error.invalid_dimensions");
                try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
                return;
            }

            File useFile = f;
            if (!fromHistory) useFile = copyToHistory(f);

            disposePreview();
            previewTex = new NativeImageBackedTexture(img);
            previewTex.setFilter(false, false);
            previewW = w; previewH = h;
            previewId = new Identifier(MOD_ID, "preview/" + System.nanoTime());
            MinecraftClient.getInstance().getTextureManager().registerTexture(previewId, previewTex);

            selectedFile = useFile;
            uploadButton.active = true;

            if (!fromHistory) {
                toastInfoKey("toast.file.selected", useFile.getName());
                try { ModSounds.play(ModSounds.UI_UPLOAD); } catch (Exception ignored) {}
            }

            loadHistory();
            tabScroll = clamp(tabScroll, 0, calcTabMaxScroll());
            rebuildPreviewPlayer();
        } catch (Exception e) {
            toastErrorKey("toast.error.read_failed");
            try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
        }
    }

    private void deleteHistoryAt(int index) {
        if (index < 0 || index >= history.size()) return;
        HistoryEntry e = history.get(index);

        try { Files.deleteIfExists(e.file.toPath()); } catch (Exception ignored) {}

        if (e.thumbId != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(e.thumbId);
        }

        history.remove(index);

        if (selectedFile != null && selectedFile.equals(e.file)) {
            selectedFile = null;
            uploadButton.active = false;
            disposePreview();
        }

        tabScroll = clamp(tabScroll, 0, calcTabMaxScroll());
    }

    private void rebuildPreviewPlayer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (previewId == null || mc.world == null) { previewPlayer = null; return; }

        final boolean slimNow = slimToggle != null && slimToggle.isChecked();
        previewSlim = slimNow;

        GameProfile profile = new GameProfile(UUID.randomUUID(), "skin_preview");
        previewPlayer = new OtherClientPlayerEntity(mc.world, profile) {
            {
                this.getDataTracker().set(PLAYER_MODEL_PARTS, (byte) 0x7F);
            }

            @Override public boolean hasSkinTexture() { return true; }
            @Override public Identifier getSkinTexture() { return previewId; }
            @Override public String getModel() { return previewSlim ? "slim" : "default"; }
        };

        previewPlayer.setPose(EntityPose.STANDING);
        previewPlayer.setSneaking(false);
        previewPlayer.setInvisible(false);

        previewPlayer.setYaw(180f);
        previewPlayer.bodyYaw = 180f;
        previewPlayer.headYaw = 180f;
        previewPlayer.setPitch(0f);
    }

    private void openHistoryFolder() {
        File dir = historyDir();
        Util.getOperatingSystem().open(dir);
    }

    private void doUpload() {
        if (selectedFile == null) {
            toastErrorKey("toast.error.read_failed");
            try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
            return;
        }
        boolean isSlim = slimToggle.isChecked();

        MinecraftClient mc = MinecraftClient.getInstance();
        Toasts.UploadToast toast = Toasts.showTrans("toast.upload.title", "toast.upload.preparing");

        try { ModSounds.play(ModSounds.UI_UPLOAD); } catch (Exception ignored) {}
        mc.setScreen(null);

        UUID playerUuid = null;
        try { if (mc.player != null) playerUuid = mc.player.getUuid(); } catch (Exception ignored) {}

        ServerApiClient.uploadSkinAsync(selectedFile, playerUuid, isSlim, new ServerApiClient.ProgressListener() {
            @Override public void onStart(long totalBytes) {
                mc.execute(() -> toast.update(0f, Text.translatable("toast.upload.start", human(totalBytes)).getString()));
            }
            @Override public void onProgress(long sent, long total) {
                float p = total > 0 ? (sent / (float) total) : 0f;
                mc.execute(() -> toast.update(p, (int)(p * 100) + "%"));
            }
            @Override public void onDone(boolean ok, String msgOrSkinId) {
                mc.execute(() -> {
                    toast.complete(ok, Text.translatable(ok ? "toast.upload.success" : "toast.upload.failed").getString());
                    if (ok) {
                        try { ModSounds.play(ModSounds.UI_COMPLETE); } catch (Exception ignored) {}
                        try {
                            var player = mc.player;
                            if (player != null) {
                                ServerApiClient.selectSkin(player.getUuid(), msgOrSkinId);
                                SkinManagerClient.setSlim(player.getUuid(), isSlim);
                                SkinManagerClient.refresh(player.getUuid());
                            }
                        } catch (Exception ignored) {}
                    } else {
                        try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
                        Toasts.error(Text.translatable("title.skin_management"),
                                Text.literal(msgOrSkinId != null ? msgOrSkinId : Text.translatable("toast.upload.failed").getString()));
                    }
                });
            }
        });
    }

    @Override
    public void filesDragged(List<Path> paths) {
        for (Path p : paths) {
            File f = p.toFile();
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                setSelectedFile(f);
                return;
            }
        }
        toastErrorKey("toast.error.drag_not_png");
        try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX >= tabX && mouseX < tabX + tabW && mouseY >= tabY && mouseY < tabY + tabH) {
            int step = u(36);
            int maxScroll = calcTabMaxScroll();
            tabScroll = clamp(tabScroll - (int)(amount * step), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (mouseX >= tabX && mouseX < tabX + tabW && mouseY >= tabY && mouseY < tabY + tabH) {
                double localX = mouseX - tabX + tabScroll;
                int stride = tabItemW + tabGap;
                int idx = (int)(localX / stride);
                if (idx >= 0 && idx < history.size()) {
                    int start = idx * stride;
                    if (localX >= start && localX <= start + tabItemW) {
                        int tx = tabX + idx * stride - tabScroll;

                        int delSize = u(12);
                        int delPad = u(4);
                        int dx = tx + tabItemW - delSize - delPad;
                        int dy = tabY + delPad;

                        if (mouseX >= dx && mouseX < dx + delSize && mouseY >= dy && mouseY < dy + delSize) {
                            deleteHistoryAt(idx);
                            return true;
                        }

                        setSelectedFile(history.get(idx).file, true);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private String ellipsis(String s, int maxPx) {
        if (textRenderer.getWidth(s) <= maxPx) return s;
        String dots = "...";
        int dotsW = textRenderer.getWidth(dots);
        int len = s.length();
        while (len > 0 && textRenderer.getWidth(s.substring(0, len)) + dotsW > maxPx) len--;
        if (len <= 0) return dots;
        return s.substring(0, len) + dots;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, C_BG);

        drawPanel(ctx, leftX, leftY, leftW, leftH);
        drawPanel(ctx, rightX, rightY, rightW, rightH);

        ctx.drawTextWithShadow(textRenderer, Text.literal("Skins"),
                tabX, tabY - u(14), C_TEXT_DIM);

        ctx.fill(tabX, tabY, tabX + tabW, tabY + tabH, C_PANEL_SOFT);
        ctx.enableScissor(tabX + 1, tabY + 1, tabX + tabW - 1, tabY + tabH - 1);

        int stride = tabItemW + tabGap;
        for (int i = 0; i < history.size(); i++) {
            int tx = tabX + i * stride - tabScroll;
            if (tx + tabItemW < tabX || tx > tabX + tabW) continue;

            HistoryEntry e = history.get(i);
            boolean sel = selectedFile != null && selectedFile.equals(e.file);
            int bg = sel ? 0x66FFFFFF : 0x22FFFFFF;
            if (mouseX >= tx && mouseX < tx + tabItemW && mouseY >= tabY && mouseY < tabY + tabH)
                bg = sel ? 0x77FFFFFF : 0x2AFFFFFF;

            ctx.fill(tx, tabY, tx + tabItemW, tabY + tabH, bg);

            if (e.thumbId != null) {
                int s = u(40);
                int px = tx + u(6);
                int py = tabY + (tabH - s) / 2;
                ctx.drawTexture(e.thumbId, px, py, 0, 0, s, s, s, s);
            }

            int delSizeTab = u(12);
            int delPadTab = u(4);
            int dxTab = tx + tabItemW - delSizeTab - delPadTab;
            int dyTab = tabY + delPadTab;

            int textX = tx + u(52);
            int nameMax = (tx + tabItemW - delSizeTab - delPadTab * 3) - textX;
            String disp = ellipsis(e.file.getName(), nameMax);

            ctx.drawTextWithShadow(textRenderer, Text.literal(disp),
                    textX, tabY + u(9), C_TEXT);
            ctx.drawTextWithShadow(textRenderer, Text.literal(e.w + "x" + e.h),
                    textX, tabY + u(26), C_TEXT_DIM);

            boolean delHoverTab = mouseX >= dxTab && mouseX < dxTab + delSizeTab
                    && mouseY >= dyTab && mouseY < dyTab + delSizeTab;

            int dbTab = delHoverTab ? 0x55FFFFFF : 0x22FFFFFF;
            ctx.fill(dxTab, dyTab, dxTab + delSizeTab, dyTab + delSizeTab, dbTab);
            ctx.fill(dxTab, dyTab, dxTab + delSizeTab, dyTab + 1, C_ACCENT);
            ctx.fill(dxTab, dyTab + delSizeTab - 1, dxTab + delSizeTab, dyTab + delSizeTab, C_ACCENT);
            ctx.fill(dxTab, dyTab, dxTab + 1, dyTab + delSizeTab, C_ACCENT);
            ctx.fill(dxTab + delSizeTab - 1, dyTab, dxTab + delSizeTab, dyTab + delSizeTab, C_ACCENT);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("X"),
                    dxTab + delSizeTab / 2, dyTab + (delSizeTab - 8) / 2, C_TEXT);
        }
        ctx.disableScissor();

        int tabMax = calcTabMaxScroll();
        drawHScrollBar(ctx, tabX, tabY, tabW, tabH, tabScroll, tabMax);

        MinecraftClient mc = MinecraftClient.getInstance();

        boolean slimNow = slimToggle != null && slimToggle.isChecked();
        if ((previewId != null) && (previewPlayer == null || slimNow != previewSlim)) {
            rebuildPreviewPlayer();
        }
        var entityToDraw = previewPlayer != null ? previewPlayer : mc.player;

        ctx.fill(dropX, dropY, dropX + dropW, dropY + dropH, 0x33000000);

        if (entityToDraw != null) {
            int cx = dropX + dropW / 2;
            int cy = dropY + dropH - u(18);
            int size = Math.min(dropW, dropH) / 3;

            ctx.enableScissor(dropX + 1, dropY + 1, dropX + dropW - 1, dropY + dropH - 1);
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 1000);
            InventoryScreen.drawEntity(ctx, cx, cy, size,
                    (float)(cx - mouseX), (float)(cy - mouseY), entityToDraw);
            ctx.getMatrices().pop();
            ctx.disableScissor();

            int hintY = clamp(cy + u(8), dropY + u(8), dropY + dropH - u(6));
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("screen.skin_management.drop_hint"),
                    cx, hintY, C_TEXT_DIM);
        }

        super.render(ctx, mouseX, mouseY, delta);

        final String __qteamWatermark = "© 2025 Q Team Studio";
        int __qteamTw = this.textRenderer.getWidth(__qteamWatermark);
        int __qteamX = this.width - __qteamTw - 6;
        int __qteamY = this.height - this.textRenderer.fontHeight - 6;
        ctx.drawText(this.textRenderer, __qteamWatermark, __qteamX, __qteamY, C_TEXT, true);
    }

    private void drawPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, C_PANEL);
        ctx.fill(x, y, x + w, y + 1, C_ACCENT);
        ctx.fill(x, y + h - 1, x + w, y + h, C_ACCENT);
        ctx.fill(x, y, x + 1, y + h, C_ACCENT);
        ctx.fill(x + w - 1, y, x + w, y + h, C_ACCENT);
    }

    private void drawHScrollBar(DrawContext ctx, int x, int y, int w, int h, int scroll, int maxScroll) {
        if (maxScroll <= 0) return;
        float ratio = w / (float)(w + maxScroll);
        int thumbW = Math.max(u(18), Math.round(w * ratio));
        int thumbX = x + Math.round((scroll / (float)maxScroll) * (w - thumbW));

        int trackY = y + h - u(3);
        ctx.fill(x + u(2), trackY, x + w - u(2), trackY + u(2), 0x44FFFFFF);
        ctx.fill(thumbX, trackY - u(1), thumbX + thumbW, trackY + u(3), 0xAAFFFFFF);
    }

    private static class TransparentButton extends ButtonWidget {
        public TransparentButton(int x, int y, int w, int h, Text message, PressAction onPress) {
            super(x, y, w, h, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void renderButton(DrawContext ctx, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean hovered = isHovered();

            int bg;
            if (!active) bg = 0x22000000;
            else bg = hovered ? 0x66FFFFFF : 0x44FFFFFF;

            ctx.fill(x, y, x + w, y + h, bg);

            int bc = active ? (hovered ? 0xFFFFFFFF : 0xDDFFFFFF) : 0x66FFFFFF;
            ctx.fill(x, y, x + w, y + 1, bc);
            ctx.fill(x, y + h - 1, x + w, y + h, bc);
            ctx.fill(x, y, x + 1, y + h, bc);
            ctx.fill(x + w - 1, y, x + w, y + h, bc);

            var tr = MinecraftClient.getInstance().textRenderer;
            int color = active ? 0xFFFFFFFF : 0x88FFFFFF;
            ctx.drawCenteredTextWithShadow(tr, getMessage(), x + w / 2, y + (h - 8) / 2, color);
        }
    }

    private static void toastInfoKey(String key, Object... args) {
        Toasts.info(Text.translatable("title.skin_management"), Text.translatable(key, args));
    }
    private static void toastErrorKey(String key, Object... args) {
        Toasts.error(Text.translatable("title.skin_management"), Text.translatable(key, args));
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
    }

    public static final class Toasts {
        private static final Identifier TOAST_TEX = new Identifier("textures/gui/toasts.png");

        public static void info(Text title, Text desc) {
            ToastManager tm = MinecraftClient.getInstance().getToastManager();
            tm.add(new SimpleToast(title, desc, 0xFFFFFFFF));
        }
        public static void error(Text title, Text desc) {
            ToastManager tm = MinecraftClient.getInstance().getToastManager();
            tm.add(new SimpleToast(title, desc, 0xFFFF5555));
        }
        public static UploadToast showTrans(String titleKey, String subKey, Object... args) {
            ToastManager tm = MinecraftClient.getInstance().getToastManager();
            UploadToast t = new UploadToast(Text.translatable(titleKey), Text.translatable(subKey, args));
            tm.add(t);
            return t;
        }

        public static ConnectionToast connection(Text title, Text checkingSub) {
            ToastManager tm = MinecraftClient.getInstance().getToastManager();
            ConnectionToast t = new ConnectionToast(title, checkingSub);
            tm.add(t);
            try { ModSounds.play(ModSounds.UI_UPLOAD); } catch (Exception ignored) {}
            return t;
        }
        public static ConnectionToast connectionTrans(String titleKey, String checkingKey) {
            return connection(Text.translatable(titleKey), Text.translatable(checkingKey));
        }

        static class SimpleToast implements net.minecraft.client.toast.Toast {
            private final Text title, desc; private final int color;
            private long start = -1;
            SimpleToast(Text title, Text desc, int color) { this.title = title; this.desc = desc; this.color = color; }

            @Override
            public Visibility draw(DrawContext ctx, net.minecraft.client.toast.ToastManager tm, long time) {
                if (start < 0) start = time;
                ctx.drawTexture(TOAST_TEX, 0, 0, 0, 0, 160, 32, 256, 256);
                var tr = MinecraftClient.getInstance().textRenderer;
                ctx.drawText(tr, title, 8, 7, color, false);
                if (desc != null) ctx.drawText(tr, desc, 8, 18, 0xFFDDDDDD, false);
                return (time - start) > 3500L ? Visibility.HIDE : Visibility.SHOW;
            }
        }

        public static class UploadToast implements net.minecraft.client.toast.Toast {
            private final Text title; private Text subtitle;
            private float progress = 0f;
            private boolean done = false, success = false;
            private long start = -1;

            public UploadToast(Text title, Text subtitle) { this.title = title; this.subtitle = subtitle; }
            public void update(float p, String sub) { progress = Math.max(0f, Math.min(1f, p)); subtitle = Text.literal(sub); }
            public void complete(boolean ok, String sub) { success = ok; done = true; subtitle = Text.literal(sub); }

            @Override
            public Visibility draw(DrawContext ctx, ToastManager tm, long time) {
                if (start < 0) start = time;
                ctx.drawTexture(TOAST_TEX, 0, 0, 0, 0, 160, 32, 256, 256);
                var tr = MinecraftClient.getInstance().textRenderer;
                ctx.drawText(tr, title, 8, 7, 0xFFFFFFFF, false);
                if (subtitle != null) ctx.drawText(tr, subtitle, 8, 18, 0xFFDDDDDD, false);

                int bx = 8, by = 28, bw = 144, bh = 2;
                ctx.fill(bx, by, bx + bw, by + bh, 0xFF444444);
                int pw = (int)(bw * progress);
                ctx.fill(bx, by, bx + pw, by + bh, success ? 0xFF55FF55 : 0xFF55AAFF);

                if (done) return (time - start) > 2200L ? Visibility.HIDE : Visibility.SHOW;
                return Visibility.SHOW;
            }
        }

        public static class ConnectionToast implements net.minecraft.client.toast.Toast {
            private final Text title;
            private final Text checking;
            private Text result;
            private boolean ok = false;
            private boolean done = false;
            private boolean announced = false;
            private long start = -1;
            private final long minShowMs = 800L;

            public ConnectionToast(Text title, Text checking) {
                this.title = title; this.checking = checking;
            }
            public void complete(boolean ok, String sub) {
                this.ok = ok;
                this.result = Text.literal(sub);
                this.done = true;
                this.announced = false;
            }

            @Override
            public Visibility draw(DrawContext ctx, ToastManager tm, long time) {
                if (start < 0) start = time;
                ctx.drawTexture(TOAST_TEX, 0, 0, 0, 0, 160, 32, 256, 256);
                var tr = MinecraftClient.getInstance().textRenderer;
                ctx.drawText(tr, title, 8, 7, 0xFFFFFFFF, false);

                long elapsed = time - start;
                boolean showResult = done && elapsed >= minShowMs;

                int color = showResult ? (ok ? 0xFF55FF55 : 0xFFFF5555) : 0xFFDDDDDD;
                Text sub = showResult ? (result != null ? result : Text.literal(ok ? "OK" : "ERROR")) : checking;
                ctx.drawText(tr, sub, 8, 18, color, false);

                if (showResult && !announced) {
                    announced = true;
                    try { ModSounds.play(ok ? ModSounds.UI_COMPLETE : ModSounds.UI_ERROR); } catch (Exception ignored) {}
                }

                if (showResult && elapsed > (minShowMs + 2000L)) return Visibility.HIDE;
                return Visibility.SHOW;
            }
        }
    }

    private static final String HEX = "0123456789abcdef";
    @SuppressWarnings("unused")
    static String sha256(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[4096]; int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            byte[] d = md.digest();
            ByteArrayOutputStream sb = new ByteArrayOutputStream(d.length * 2);
            for (byte b : d) {
                int hi = (b >> 4) & 0xF, lo = b & 0xF;
                sb.write(HEX.charAt(hi));
                sb.write(HEX.charAt(lo));
            }
            return sb.toString();
        } catch (Exception e) { return "unknown"; }
    }
}
