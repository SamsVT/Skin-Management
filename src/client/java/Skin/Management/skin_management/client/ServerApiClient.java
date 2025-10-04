package Skin.Management.skin_management.client;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ServerApiClient {

    public interface ProgressListener {
        default void onStart(long totalBytes) {}
        default void onProgress(long sent, long total) {}
        default void onDone(boolean ok, String msgOrSkinId) {}
    }

    public record SelectedSkin(String url, boolean slim) {}

    private static final int TIMEOUT_MS = 15000;

    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ServerApiClient-Worker");
        t.setDaemon(true);
        return t;
    });


    private static final String FIXED_BASE = "https://storage-api.catskin.space";


    @SuppressWarnings("unused")
    private static volatile boolean PROD = true;
    @SuppressWarnings("unused")
    private static volatile String BASE_DEV = FIXED_BASE;
    @SuppressWarnings("unused")
    private static volatile String BASE_PROD = FIXED_BASE;
    @SuppressWarnings("unused")
    private static volatile String BASE_OVERRIDE = FIXED_BASE;

    private static volatile String PATH_UPLOAD   = "/upload";
    private static volatile String PATH_SELECT   = "/select";
    private static volatile String PATH_SELECTED = "/selected";
    private static volatile String PATH_PUBLIC   = "/public/";
    private static volatile String PATH_EVENTS   = "/events";

    private static volatile String AUTH_TOKEN = null;

    // เมธอดเหล่านี้คงไว้เพื่อความเข้ากันได้ แต่ไม่มีผล (ฐานถูกล็อกแล้ว)
    public static void setProd(boolean prod) { /* no-op: base locked */ }
    public static void setBase(String baseUrl) { /* no-op: base locked */ }

    public static void setAuthToken(String bearerToken) { AUTH_TOKEN = bearerToken; }
    public static void setPaths(String upload, String select, String selected, String publicPath) {
        if (upload != null) PATH_UPLOAD = upload;
        if (select != null) PATH_SELECT = select;
        if (selected != null) PATH_SELECTED = selected;
        if (publicPath != null) PATH_PUBLIC = publicPath.endsWith("/") ? publicPath : publicPath + "/";
    }

    private static String userAgent() { return "skin_management/ServerApiClient"; }

    // ใช้ FIXED_BASE เสมอ
    private static String base() { return trimSlash(FIXED_BASE); }

    private static String trimSlash(String s) {
        if (s == null) return "";
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == '/') n--;
        return n == s.length() ? s : s.substring(0, n);
    }

    private static String join(String a, String b) {
        if (b == null || b.isBlank()) return a;
        if (b.startsWith("/")) return a + b;
        return a + "/" + b;
    }

    private static boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static HttpURLConnection open(String method, String pathOrUrl, String contentType) throws IOException {
        String url = isHttp(pathOrUrl) ? pathOrUrl : join(base(), pathOrUrl);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", userAgent());
        if (contentType != null) c.setRequestProperty("Content-Type", contentType);
        if (AUTH_TOKEN != null && !AUTH_TOKEN.isBlank()) c.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);
        if ("POST".equals(method) || "PUT".equals(method)) c.setDoOutput(true);
        return c;
    }

    public static void uploadSkinAsync(File file, UUID playerUuid, boolean isSlim, ProgressListener cb) {
        CompletableFuture.runAsync(() -> {
            try {
                String boundary = "----SM-" + System.nanoTime();
                HttpURLConnection c = open("POST", PATH_UPLOAD, "multipart/form-data; boundary=" + boundary);

                long total = file.length();
                cb.onStart(total);

                try (OutputStream out0 = c.getOutputStream();
                     CountingOutputStream out = new CountingOutputStream(out0, total, cb);
                     PrintWriter w = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true)) {

                    w.append("--").append(boundary).append("\r\n");
                    if (playerUuid != null) {
                        w.append("Content-Disposition: form-data; name=\"uuid\"").append("\r\n\r\n");
                        w.append(playerUuid.toString()).append("\r\n");
                    }

                    w.append("--").append(boundary).append("\r\n");
                    w.append("Content-Disposition: form-data; name=\"slim\"").append("\r\n\r\n");
                    w.append(isSlim ? "true" : "false").append("\r\n");

                    w.append("--").append(boundary).append("\r\n");
                    w.append("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"").append("\r\n");
                    w.append("Content-Type: image/png").append("\r\n\r\n");
                    w.flush();

                    try (InputStream in = new FileInputStream(file)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    }
                    out.flush();
                    w.append("\r\n").flush();

                    w.append("--").append(boundary).append("--").append("\r\n");
                    w.flush();
                }

                int code = c.getResponseCode();
                String body = readBody(c);
                if (code / 100 == 2) {
                    String id = jsonStr(body, "id");
                    if (id == null || id.isEmpty()) {
                        String url = jsonStr(body, "url");
                        if (url != null && !url.isEmpty()) id = url;
                        else id = body != null ? body.trim() : "ok";
                    }
                    cb.onDone(true, id);
                } else {
                    cb.onDone(false, (body == null || body.isBlank()) ? ("HTTP " + code) : body);
                }
            } catch (Exception e) {
                cb.onDone(false, e.getMessage());
            }
        }, EXEC);
    }

    public static void selectSkin(UUID playerUuid, String skinIdOrUrl) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection c = open("POST", PATH_SELECT, "application/json; charset=utf-8");
                String body = "{\"uuid\":\"" + playerUuid + "\",\"skin\":\"" + escJson(skinIdOrUrl) + "\"}";
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code / 100 != 2) readBody(c);
            } catch (Exception ignored) {}
        }, EXEC);
    }

    public static CompletableFuture<SelectedSkin> fetchSelectedAsync(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String q = PATH_SELECTED.contains("?") ? (PATH_SELECTED + "&uuid=" + playerUuid) : (PATH_SELECTED + "?uuid=" + playerUuid);
                HttpURLConnection c = open("GET", q, null);
                int code = c.getResponseCode();
                String body = readBody(c);
                if (code / 100 != 2) throw new IOException("HTTP " + code + ": " + body);
                String url = jsonStr(body, "url");
                if (url == null || url.isBlank()) {
                    String id = jsonStr(body, "id");
                    if (id != null && !id.isBlank()) url = endpointPublicPng(id);
                }
                boolean slim = jsonBool(body, "slim", false);
                if (url == null || url.isBlank()) return null;
                return new SelectedSkin(url, slim);
            } catch (Exception e) {
                return null;
            }
        }, EXEC);
    }

    public static CompletableFuture<NativeImageBackedTexture> downloadTextureAsync(String urlOrPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection c = open("GET", urlOrPath, null);
                int code = c.getResponseCode();
                if (code / 100 != 2) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    NativeImage image = NativeImage.read(in);
                    return new NativeImageBackedTexture(image);
                }
            } catch (Exception e) {
                return null;
            }
        }, EXEC);
    }

    public static String endpointPublicPng(String id) {
        if (id == null) return null;
        if (id.endsWith(".png")) return PATH_PUBLIC.startsWith("/") ? join(base(), PATH_PUBLIC + id) : join(base(), "/" + PATH_PUBLIC + id);
        return PATH_PUBLIC.startsWith("/") ? join(base(), PATH_PUBLIC + id + ".png") : join(base(), "/" + PATH_PUBLIC + id + ".png");
    }

    public static String ping() {
        try {
            HttpURLConnection c = open("GET", "/", null);
            c.getResponseCode();
            String body = readBody(c);
            return (body == null || body.isBlank()) ? "OK" : body;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static CompletableFuture<Boolean> pingAsyncOk() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection c = open("GET", "/", null);
                int code = c.getResponseCode();
                return (code / 100) == 2;
            } catch (Exception e) {
                return false;
            }
        }, EXEC);
    }

    private static String readBody(HttpURLConnection c) {
        try (InputStream in = c.getResponseCode() / 100 == 2 ? c.getInputStream() : c.getErrorStream()) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String escJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    private static String jsonStr(String body, String key) {
        if (body == null) return null;
        String pat = "\"" + key + "\"";
        int i = body.indexOf(pat);
        if (i < 0) return null;
        i = body.indexOf(':', i);
        if (i < 0) return null;
        int q1 = body.indexOf('"', i + 1);
        if (q1 < 0) return null;
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return body.substring(q1 + 1, q2);
    }

    private static boolean jsonBool(String body, String key, boolean def) {
        if (body == null) return def;
        String pat = "\"" + key + "\"";
        int i = body.indexOf(pat);
        if (i < 0) return def;
        i = body.indexOf(':', i);
        if (i < 0) return def;
        int j = i + 1;
        while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
        if (j >= body.length()) return def;
        if (body.regionMatches(true, j, "true", 0, 4)) return true;
        if (body.regionMatches(true, j, "false", 0, 5)) return false;
        if (body.regionMatches(true, j, "1", 0, 1)) return true;
        if (body.regionMatches(true, j, "0", 0, 1)) return false;
        return def;
    }

    static final class CountingOutputStream extends OutputStream {
        final OutputStream delegate;
        final long total;
        final ProgressListener cb;
        long sent = 0;

        CountingOutputStream(OutputStream delegate, long total, ProgressListener cb) {
            this.delegate = delegate; this.total = total; this.cb = cb;
        }
        @Override public void write(int b) throws IOException {
            delegate.write(b); sent++; cb.onProgress(sent, total);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len); sent += len; cb.onProgress(sent, total);
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    // ====== SSE (Server-Sent Events) for live updates ======
    public static final class UpdateEvent {
        public final UUID uuid;
        public final String id;
        public final String url;
        public final Boolean slim;
        public UpdateEvent(UUID uuid, String id, String url, Boolean slim) {
            this.uuid = uuid; this.id = id; this.url = url; this.slim = slim;
        }
    }

    private static volatile Thread SSE_THREAD = null;
    private static volatile boolean SSE_STOP = false;

    public static void startSse(Consumer<UpdateEvent> onUpdate) {
        if (SSE_THREAD != null) return;
        SSE_STOP = false;
        SSE_THREAD = new Thread(() -> {
            while (!SSE_STOP) {
                HttpURLConnection c = null;
                try {
                    c = openSse(PATH_EVENTS);
                    int code = c.getResponseCode();
                    if (code / 100 != 2) {
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!SSE_STOP && (line = br.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                String json = line.substring(5).trim();
                                UpdateEvent evt = parseUpdateEvent(json);
                                if (evt != null && evt.uuid != null && onUpdate != null) {
                                    onUpdate.accept(evt);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored2) {}
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "ServerApiClient-SSE");
        SSE_THREAD.setDaemon(true);
        SSE_THREAD.start();
    }

    public static void stopSse() {
        SSE_STOP = true;
        Thread t = SSE_THREAD;
        SSE_THREAD = null;
        if (t != null) t.interrupt();
    }

    private static UpdateEvent parseUpdateEvent(String json) {
        try {
            String uuidStr = jsonStr(json, "uuid");
            String id = jsonStr(json, "id");
            String url = jsonStr(json, "url");
            Boolean slim = null;
            if (json.contains("\"slim\"")) slim = jsonBool(json, "slim", false);

            UUID uuid = null;
            if (uuidStr != null && !uuidStr.isBlank()) {
                uuid = parseUuidFlexible(uuidStr);
            }
            return new UpdateEvent(uuid, id, url, slim);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID parseUuidFlexible(String s) {
        String t = s.replace("-", "");
        if (t.length() == 32) {
            String dashed = t.substring(0,8)+"-"+t.substring(8,12)+"-"+t.substring(12,16)+"-"+t.substring(16,20)+"-"+t.substring(20);
            try { return UUID.fromString(dashed); } catch (Exception ignored) {}
        }
        try { return UUID.fromString(s); } catch (Exception ignored) {}
        return null;
    }

    private static HttpURLConnection openSse(String pathOrUrl) throws IOException {
        String url = isHttp(pathOrUrl) ? pathOrUrl : join(base(), pathOrUrl);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(0); // ไม่ timeout ระหว่างสตรีม
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", userAgent());
        if (AUTH_TOKEN != null && !AUTH_TOKEN.isBlank()) c.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);
        return c;
    }
}