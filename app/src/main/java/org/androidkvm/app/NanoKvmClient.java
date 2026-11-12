package org.androidkvm.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Direct client for the open-source NanoKVM web API and HID report WebSocket. */
final class NanoKvmClient extends PikvmClient {
    private OkHttpClient socketClient;
    private volatile WebSocket inputSocket;
    private volatile boolean inputReady;
    private volatile CountDownLatch inputOpening = new CountDownLatch(0);
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            WebSocket socket = inputSocket;
            if (inputReady && socket != null && socket.send(ByteString.of(new byte[]{0}))) {
                heartbeatHandler.postDelayed(this, 10000);
            }
        }
    };
    private int buttons;
    private int mouseX = 16384, mouseY = 16384;
    private volatile boolean mjpegVideo;

    NanoKvmClient(String address, String fingerprint) throws Exception {
        super(address, fingerprint, true);
    }

    private HttpURLConnection connectNano(String path, String method, byte[] body) throws IOException {
        return connectNano(path, method, body, "application/json; charset=utf-8");
    }

    private HttpURLConnection connectNano(String path, String method, byte[] body,
                                           String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        if (connection instanceof HttpsURLConnection && pinned) {
            HttpsURLConnection secure = (HttpsURLConnection) connection;
            secure.setSSLSocketFactory(pinnedFactory);
            secure.setHostnameVerifier((host, session) -> true);
        }
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(path.startsWith("/api/stream/") ? 30000
                : "/api/vm/script/run".equals(path) ? 300000
                : "/api/network/wifi/connect".equals(path) ? 45000
                : "/api/network/wifi/disconnect".equals(path) ? 30000 : 12000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        return connection;
    }

    private JSONObject requestNano(String path, String method, JSONObject body) throws IOException {
        return requestNanoBytes(path, method,
                body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8");
    }

    private JSONObject requestNanoBytes(String path, String method, byte[] body,
                                        String contentType) throws IOException {
        HttpURLConnection connection = connectNano(path, method, body, contentType);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            int limit = "/api/vm/script/run".equals(path) ? 1048576 : 65536;
            String raw = stream == null ? "" : new String(readLimited(stream, limit), StandardCharsets.UTF_8);
            JSONObject response = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            if (status != 200 || response.optInt("code", -1) != 0) {
                String reason = response.optString("msg", "NanoKVM request failed");
                throw new ApiException(status, reason + " (HTTP " + status + ")");
            }
            return response.optJSONObject("data") == null ? new JSONObject() : response.optJSONObject("data");
        } catch (JSONException error) {
            throw new IOException("Invalid NanoKVM response", error);
        } finally {
            connection.disconnect();
        }
    }

    @Override void login(String user, String password) throws IOException {
        try {
            JSONObject form = new JSONObject().put("username", user)
                    .put("password", encodeNativePassword(password));
            HttpURLConnection connection = connectNano("/api/auth/login", "POST",
                    form.toString().getBytes(StandardCharsets.UTF_8));
            try {
                int status = connection.getResponseCode();
                InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
                JSONObject response = new JSONObject(stream == null ? "{}" : new String(
                        readLimited(stream, 65536), StandardCharsets.UTF_8));
                if (status != 200 || response.optInt("code", -1) != 0) {
                    throw new ApiException(status, "NanoKVM login failed: "
                            + response.optString("msg", "HTTP " + status));
                }
                String setCookie = connection.getHeaderField("Set-Cookie");
                if (setCookie == null || !setCookie.contains("=")) {
                    throw new IOException("NanoKVM did not return a login session. Enable authentication on the KVM.");
                }
                cookie = setCookie.split(";", 2)[0];
            } finally {
                connection.disconnect();
            }
            requestNano("/api/auth/account", "GET", null);
        } catch (IOException error) {
            cookie = null;
            throw error;
        } catch (Exception error) {
            cookie = null;
            throw new IOException("NanoKVM login failed", error);
        }
    }

    @Override boolean hasNativeVideo() { return !mjpegVideo; }

    void restoreVideoMode(String mode) throws IOException {
        if (!"mjpeg".equals(mode) && !"h264".equals(mode)) mode = "h264";
        setVideoParameter("mode", mode);
    }

    @Override JSONObject videoSettings() {
        // NanoKVM's screen endpoint writes settings but has no read endpoint.
        return new JSONObject();
    }

    @Override void setVideoParameter(String name, String value) throws IOException {
        String type;
        if ("mode".equals(name)) {
            if (!"mjpeg".equals(value) && !"h264".equals(value)) {
                throw new IllegalArgumentException("Unsupported video mode.");
            }
            type = "type";
        } else if ("desired_fps".equals(name)) {
            type = "fps";
        } else if ("quality".equals(name)) {
            type = "quality";
        } else if ("gop".equals(name)) {
            type = "gop";
        } else {
            throw new IllegalArgumentException("NanoKVM does not offer capture resolution control.");
        }
        try {
            requestNano("/api/vm/screen", "POST", new JSONObject()
                    .put("type", type).put("value", "mode".equals(name)
                            ? ("mjpeg".equals(value) ? 0 : 1) : Integer.parseInt(value)));
            if ("mode".equals(name)) mjpegVideo = "mjpeg".equals(value);
        } catch (JSONException | NumberFormatException error) {
            throw new IOException("Invalid video value", error);
        }
    }

    @Override void resetHid() throws IOException {
        requestNano("/api/hid/reset", "POST", new JSONObject());
    }

    static boolean safeScriptName(String name) {
        return name != null && name.matches("(?i)[a-z0-9][a-z0-9._-]{0,95}\\.(sh|py)");
    }

    List<String> listScripts() throws IOException {
        JSONArray files = requestNano("/api/vm/script", "GET", null).optJSONArray("files");
        List<String> result = new ArrayList<>();
        if (files != null) for (int index = 0; index < files.length(); index++) {
            String name = files.optString(index, "");
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }

    String runScript(String name, boolean background) throws IOException {
        requireSafeScriptName(name);
        try {
            JSONObject result = requestNano("/api/vm/script/run", "POST", new JSONObject()
                    .put("name", name).put("type", background ? "background" : "foreground"));
            return result.optString("log", "");
        } catch (JSONException error) {
            throw new IOException("Invalid script request", error);
        }
    }

    void deleteScript(String name) throws IOException {
        requireSafeScriptName(name);
        try {
            requestNano("/api/vm/script", "DELETE", new JSONObject().put("name", name));
        } catch (JSONException error) {
            throw new IOException("Invalid script request", error);
        }
    }

    void uploadScript(String name, byte[] script) throws IOException {
        requireSafeScriptName(name);
        if (script.length > 1048576) throw new IOException("Script exceeds 1 MB");
        String boundary = "androidKVM-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + script.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(script, 0, body, prefix.length, script.length);
        System.arraycopy(suffix, 0, body, prefix.length + script.length, suffix.length);
        requestNanoBytes("/api/vm/script/upload", "POST", body,
                "multipart/form-data; boundary=" + boundary);
    }

    private static void requireSafeScriptName(String name) throws IOException {
        if (!safeScriptName(name)) throw new IOException("Use a simple .sh or .py filename with letters, numbers, dots, underscores, or hyphens.");
    }

    JSONObject setting(String path) throws IOException {
        if (!path.startsWith("/api/")) throw new IllegalArgumentException("Invalid NanoKVM setting path");
        return requestNano(path, "GET", null);
    }

    void updateSetting(String path, JSONObject value) throws IOException {
        if (!path.startsWith("/api/")) throw new IllegalArgumentException("Invalid NanoKVM setting path");
        requestNano(path, "POST", value == null ? new JSONObject() : value);
    }

    void changePassword(String currentPassword, String newPassword) throws Exception {
        updateSetting("/api/auth/password", new JSONObject()
                .put("currentPassword", encodeNativePassword(currentPassword))
                .put("password", encodeNativePassword(newPassword)));
    }

    WebSocket openTerminal(WebSocketListener listener) {
        return socketClient().newWebSocket(new Request.Builder()
                .url(socketUrl("/api/vm/terminal"))
                .header("Cookie", cookie).build(), listener);
    }

    private synchronized OkHttpClient socketClient() {
        if (socketClient == null) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS);
            if (pinned) {
                builder.sslSocketFactory(pinnedFactory, pinTrust);
                builder.hostnameVerifier((host, session) -> true);
            }
            socketClient = builder.build();
        }
        return socketClient;
    }

    private String socketUrl(String path) {
        return (baseUrl.startsWith("https://") ? "wss://" + baseUrl.substring(8)
                : "ws://" + baseUrl.substring(7)) + path;
    }

    @Override WebSocket openNativeVideo(WebSocketListener listener) {
        return socketClient().newWebSocket(new Request.Builder()
                .url(socketUrl("/api/stream/h264/direct?flow=2"))
                .header("Cookie", cookie).build(), listener);
    }

    @Override HttpURLConnection openVideo() throws IOException {
        HttpURLConnection connection = connectNano("/api/stream/mjpeg", "GET", null);
        connection.setRequestProperty("Accept", "multipart/x-mixed-replace");
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new ApiException(status, "NanoKVM video unavailable (HTTP " + status + ")");
        }
        return connection;
    }

    @Override PowerState powerState() throws IOException {
        JSONObject gpio = requestNano("/api/vm/gpio", "GET", null);
        return new PowerState(true, gpio.optBoolean("pwr"));
    }

    @Override void setPower(boolean on) throws IOException {
        if (powerState().on == on) return;
        try {
            requestNano("/api/vm/gpio", "POST", new JSONObject().put("type", "power")
                    .put("duration", 800));
        } catch (JSONException error) {
            throw new IOException("Could not create power request", error);
        }
    }

    @Override String wakeFromKvm(String mac, String gpioChannel) throws IOException {
        try {
            requestNano("/api/network/wol", "POST", new JSONObject().put("mac", mac));
            return "NanoKVM sent the wake packet on its LAN.";
        } catch (JSONException error) {
            throw new IOException("Could not create wake request", error);
        }
    }

    @Override synchronized void ensureEvents() {
        if (cookie == null || inputSocket != null) return;
        inputReady = false;
        CountDownLatch opening = new CountDownLatch(1);
        inputOpening = opening;
        inputSocket = socketClient().newWebSocket(new Request.Builder()
                .url(socketUrl("/api/ws")).header("Cookie", cookie).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                synchronized (NanoKvmClient.this) {
                    if (inputSocket == socket) {
                        inputReady = true;
                        heartbeatHandler.removeCallbacks(heartbeat);
                        heartbeatHandler.postDelayed(heartbeat, 10000);
                    }
                    opening.countDown();
                }
            }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                clearSocket(socket, opening);
            }
            @Override public void onClosed(WebSocket socket, int code, String reason) {
                clearSocket(socket, opening);
            }
        });
    }

    private synchronized void clearSocket(WebSocket socket, CountDownLatch opening) {
        if (inputSocket == socket) {
            inputReady = false;
            inputSocket = null;
            heartbeatHandler.removeCallbacks(heartbeat);
        }
        opening.countDown();
    }

    private void report(byte[] bytes) throws IOException {
        ensureEvents();
        try {
            if (!inputOpening.await(4, TimeUnit.SECONDS)) throw new IOException("NanoKVM input did not connect.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("NanoKVM input interrupted", error);
        }
        WebSocket socket = inputSocket;
        if (!inputReady || socket == null || !socket.send(ByteString.of(bytes))) {
            throw new IOException("NanoKVM input connection is unavailable.");
        }
    }

    @Override synchronized void stopEvents() {
        WebSocket socket = inputSocket;
        buttons = 0;
        if (socket != null && inputReady) {
            socket.send(ByteString.of(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0}));
            socket.send(ByteString.of(mouseReport(0)));
        }
        inputReady = false;
        inputSocket = null;
        heartbeatHandler.removeCallbacks(heartbeat);
        inputOpening.countDown();
        if (socket != null) socket.close(1000, "App paused");
    }

    @Override void logout() {
        stopEvents();
        try { requestNano("/api/auth/logout", "POST", new JSONObject()); }
        catch (Exception ignored) { }
        cookie = null;
    }

    @Override void sendKey(String code, List<String> modifiers) throws IOException {
        int key = keyCode(code);
        if (key < 0) throw new IOException("Unsupported NanoKVM key: " + code);
        int bits = 0;
        for (String modifier : modifiers) {
            int bit = modifierBit(modifier);
            if (bit < 0) throw new IOException("Unsupported modifier: " + modifier);
            bits |= bit;
        }
        byte[] down = new byte[9];
        down[0] = 1;
        if (key >= 0xE0 && key <= 0xE7) bits |= 1 << (key - 0xE0);
        else down[3] = (byte) key;
        down[1] = (byte) bits;
        report(down);
        report(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    @Override void sendText(String text) throws IOException {
        if (text.isEmpty()) return;
        try {
            requestNano("/api/hid/paste", "POST", new JSONObject().put("content", text)
                    .put("langue", "en"));
        } catch (JSONException error) {
            throw new IOException("Could not create text request", error);
        }
    }

    @Override synchronized void moveMouse(float x, float y) throws IOException {
        mouseX = Math.max(0, Math.min(32767, Math.round(x * 32767)));
        mouseY = Math.max(0, Math.min(32767, Math.round(y * 32767)));
        report(mouseReport(0));
    }

    @Override synchronized void mouseClick(String button, int count) throws IOException {
        for (int n = 0; n < count; n++) {
            mouseButton(button, true);
            mouseButton(button, false);
        }
    }

    @Override synchronized void mouseButton(String button, boolean down) throws IOException {
        int bit;
        switch (button.toLowerCase(Locale.ROOT)) {
            case "left": bit = 1; break;
            case "right": bit = 2; break;
            case "middle": bit = 4; break;
            default: throw new IOException("Unsupported mouse button: " + button);
        }
        buttons = down ? buttons | bit : buttons & ~bit;
        report(mouseReport(0));
    }

    @Override synchronized void mouseWheel(int steps) throws IOException {
        report(mouseReport(Math.max(-127, Math.min(127, steps))));
    }

    private byte[] mouseReport(int wheel) {
        return new byte[]{2, (byte) buttons, (byte) mouseX, (byte) (mouseX >> 8),
                (byte) mouseY, (byte) (mouseY >> 8), (byte) wheel};
    }

    private static int modifierBit(String code) {
        switch (code) {
            case "ControlLeft": return 1;
            case "ShiftLeft": return 2;
            case "AltLeft": return 4;
            case "MetaLeft": return 8;
            case "ControlRight": return 16;
            case "ShiftRight": return 32;
            case "AltRight": return 64;
            case "MetaRight": return 128;
            default: return -1;
        }
    }

    private static int keyCode(String code) {
        if (code.length() == 4 && code.startsWith("Key")) {
            char letter = code.charAt(3);
            if (letter >= 'A' && letter <= 'Z') return 4 + letter - 'A';
        }
        if (code.length() == 6 && code.startsWith("Digit")) {
            char digit = code.charAt(5);
            if (digit >= '1' && digit <= '9') return 0x1E + digit - '1';
            if (digit == '0') return 0x27;
        }
        if (code.matches("F([1-9]|1[0-2])")) return 0x39 + Integer.parseInt(code.substring(1));
        int modifier = modifierBit(code);
        if (modifier >= 0) return 0xE0 + Integer.numberOfTrailingZeros(modifier);
        switch (code) {
            case "Enter": return 0x28;
            case "Escape": return 0x29;
            case "Backspace": return 0x2A;
            case "Tab": return 0x2B;
            case "Space": return 0x2C;
            case "Minus": return 0x2D;
            case "Equal": return 0x2E;
            case "BracketLeft": return 0x2F;
            case "BracketRight": return 0x30;
            case "Backslash": return 0x31;
            case "Semicolon": return 0x33;
            case "Quote": return 0x34;
            case "Backquote": return 0x35;
            case "Comma": return 0x36;
            case "Period": return 0x37;
            case "Slash": return 0x38;
            case "Insert": return 0x49;
            case "Home": return 0x4A;
            case "PageUp": return 0x4B;
            case "Delete": return 0x4C;
            case "End": return 0x4D;
            case "PageDown": return 0x4E;
            case "ArrowRight": return 0x4F;
            case "ArrowLeft": return 0x50;
            case "ArrowDown": return 0x51;
            case "ArrowUp": return 0x52;
            default: return -1;
        }
    }
}
