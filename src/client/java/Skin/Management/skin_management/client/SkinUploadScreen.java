package Skin.Management.skin_management.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.EventQueue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class SkinUploadScreen extends Screen {
    private static final float UI_SCALE = 1.00f;
    private static final float TOP_YR   = 0.20f;
    private static final float BOT_YR   = 0.82f;
    private static final int   BOX_PAD  = 12;
    private static final int   DASH_COLOR = 0xCCFFFFFF;

    private int u(int px){ return Math.max(1, Math.round(px * UI_SCALE)); }
    private static final String MOD_ID = "skin_management";

    private File selectedFile;
    private CheckboxWidget slimToggle;
    private ButtonWidget chooseButton, uploadButton;

    private Identifier previewId;
    private NativeImageBackedTexture previewTex;
    private int previewW, previewH;

    private int zoneTop, zoneBot;
    private int dropX, dropY, dropW, dropH;
    private int controlsY, modelY;

    public SkinUploadScreen() { super(Text.translatable("screen.skin_management.title")); }

    @Override
    protected void init() {
        zoneTop = Math.round(this.height * TOP_YR);
        zoneBot = Math.round(this.height * BOT_YR);
        if (zoneBot <= zoneTop + u(140)) zoneBot = zoneTop + u(140);

        int rowH   = u(20);
        int gap    = u(16);
        int marginBetween = u(10);
        int btnW   = Math.max(u(160), this.width / 6);

        int bandTop = zoneTop + u(BOX_PAD);
        int bandBot = zoneBot - u(BOX_PAD);

        int fontH = textRenderer.fontHeight;
        int reserveBelow = rowH + u(6) + fontH;

        int availH = (bandBot - bandTop) - reserveBelow - marginBetween;
        int size   = Math.min(availH, (int)(this.width * 0.68f));
        size       = Math.max(u(140), size);

        dropW = dropH = size;
        dropX = (this.width - size) / 2;
        dropY = bandTop + (availH - size) / 2;

        controlsY = dropY + dropH + marginBetween;

        int checkBoxSize = u(20);
        int labelGap     = u(6);
        int labelW       = textRenderer.getWidth(Text.translatable("screen.skin_management.slim_model"));
        int midW         = checkBoxSize + labelGap + labelW;

        int centerX = this.width / 2;
        int midX    = centerX - midW / 2;
        int leftX   = midX - gap - btnW;
        int rightX  = midX + midW + gap;

        clearChildren();

        chooseButton = ButtonWidget.builder(Text.translatable("screen.skin_management.button.select_png"), b -> openFilePicker())
                .dimensions(leftX, controlsY, btnW, rowH).build();
        addDrawableChild(chooseButton);

        slimToggle = new CheckboxWidget(midX, controlsY, midW, rowH, Text.translatable("screen.skin_management.slim_model"), false);
        addDrawableChild(slimToggle);

        uploadButton = ButtonWidget.builder(Text.translatable("screen.skin_management.button.upload"), b -> doUpload())
                .dimensions(rightX, controlsY, btnW, rowH).build();
        uploadButton.active = (selectedFile != null);
        addDrawableChild(uploadButton);

        modelY = controlsY + rowH + u(6);
    }

    @Override public void resize(MinecraftClient c, int w, int h){ super.resize(c, w, h); this.init(); }

    @Override
    public void close() {
        disposePreview();
        super.close();
    }

    private void disposePreview() {
        if (previewId != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(previewId);
            previewId = null;
        }
        previewTex = null; previewW = previewH = 0;
    }

    // =============== เลือกไฟล์ + พรีวิว (เล่นเสียงตามผล) ===============
    private void setSelectedFile(File f) {
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
            boolean inRange = (w >= 64 && w <= 8192);
            if (!(square && pow2 && inRange)) {
                img.close();
                toastErrorKey("toast.error.invalid_dimensions");
                try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
                return;
            }

            disposePreview();
            previewTex = new NativeImageBackedTexture(img);
            previewTex.setFilter(false, false);
            previewW = w; previewH = h;
            previewId = new Identifier(MOD_ID, "preview/" + System.nanoTime());
            MinecraftClient.getInstance().getTextureManager().registerTexture(previewId, previewTex);

            selectedFile = f;
            uploadButton.active = true;
            toastInfoKey("toast.file.selected", f.getName());
            try { ModSounds.play(ModSounds.UI_UPLOAD); } catch (Exception ignored) {}

        } catch (Exception e) {
            toastErrorKey("toast.error.read_failed");
            try { ModSounds.play(ModSounds.UI_ERROR); } catch (Exception ignored) {}
        }
    }

    private void openFilePicker() {
        try {
            EventQueue.invokeLater(() -> {
                try {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle(Text.translatable("screen.skin_management.button.select_png").getString());
                    chooser.setFileFilter(new FileNameExtensionFilter("PNG Images", "png"));
                    int res = chooser.showOpenDialog(null);
                    if (res == JFileChooser.APPROVE_OPTION) {
                        File f = chooser.getSelectedFile();
                        if (f != null) MinecraftClient.getInstance().execute(() -> setSelectedFile(f));
                    }
                } catch (Throwable t) {
                    MinecraftClient.getInstance().execute(this::openFolderFallback);
                }
            });
        } catch (Throwable t) {
            openFolderFallback();
        }
    }

    private void openFolderFallback() {
        File dropDir = new File(MinecraftClient.getInstance().runDirectory, "skin_uploads");
        if (!dropDir.exists() && !dropDir.mkdirs()) {
            toastErrorKey("toast.error.create_folder_failed");
            return;
        }
        Util.getOperatingSystem().open(dropDir);
        toastInfoKey("toast.folder.opened");
    }

    // ================= อัปโหลด (ปิด UI + เสียง + Toast เดียว) =================
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

    // ================= Drag & Drop =================
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

    // ================= Render =================
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x22000000);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.skin_management.header"), this.width / 2, u(12), 0xFFFFFFFF);

        ctx.fill(dropX, dropY, dropX + dropW, dropY + dropH, 0x22000000);
        dashedRect(ctx, dropX, dropY, dropW, dropH, DASH_COLOR, u(8), u(8), u(2));

        if (previewId != null) {
            float maxW = dropW - u(20), maxH = dropH - u(20);
            float scale = Math.min(maxW / previewW, maxH / previewH);
            int drawW = Math.round(previewW * scale);
            int drawH = Math.round(previewH * scale);
            int px = dropX + (dropW - drawW) / 2;
            int py = dropY + (dropH - drawH) / 2;

            ctx.enableScissor(dropX + 1, dropY + 1, dropX + dropW - 1, dropY + dropH - 1);
            ctx.getMatrices().push();
            ctx.getMatrices().translate(px, py, 0);
            ctx.getMatrices().scale(scale, scale, 1f);
            ctx.drawTexture(previewId, 0, 0, 0, 0, previewW, previewH, previewW, previewH);
            ctx.getMatrices().pop();
            ctx.disableScissor();
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.skin_management.drop_hint"),
                    dropX + dropW / 2, dropY + dropH / 2 - u(4), 0xFFFFFFFF);
        }

        super.render(ctx, mouseX, mouseY, delta);

        Text modeTxt = Text.translatable(slimToggle.isChecked() ? "screen.skin_management.mode.slim" : "screen.skin_management.mode.classic");
        Text line = Text.translatable("screen.skin_management.mode.label", modeTxt);
        ctx.drawCenteredTextWithShadow(textRenderer, line, this.width / 2, modelY, 0xFFDDDDDD);

        final String __qteamWatermark = "© 2025 Q Team Studio";
        int __qteamTw = this.textRenderer.getWidth(__qteamWatermark);
        int __qteamX = this.width - __qteamTw - 6;
        int __qteamY = this.height - this.textRenderer.fontHeight - 6;
        ctx.drawText(this.textRenderer, __qteamWatermark, __qteamX, __qteamY, 0xFFFFFFFF, true);
    }

    private void dashedRect(DrawContext ctx, int x, int y, int w, int h, int color, int dash, int gap, int thick) {
        for (int i = 0; i < w; i += dash + gap) {
            int len = Math.min(dash, w - i);
            ctx.fill(x + i, y, x + i + len, y + thick, color);
            ctx.fill(x + i, y + h - thick, x + i + len, y + h, color);
        }
        for (int i = 0; i < h; i += dash + gap) {
            int len = Math.min(dash, h - i);
            ctx.fill(x, y + i, x + thick, y + i + len, color);
            ctx.fill(x + w - thick, y + i, x + w, y + i + len, color);
        }
    }

    // Toast helpers ที่ใช้คีย์แปล
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

    // ================= Toasts (เพิ่ม showTrans/connectionTrans) =================
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

        // ใช้ Text แทน String เพื่อรองรับแปล
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
            byte[] buf = new byte[8192]; int r;
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