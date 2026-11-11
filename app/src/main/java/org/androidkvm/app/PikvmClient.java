package org.androidkvm.app;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Small PiKVM HTTP client. All methods run off the UI thread. */
class PikvmClient {
    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class PowerState {
        final boolean enabled;
        final boolean on;
        PowerState(boolean enabled, boolean on) {
            this.enabled = enabled;
            this.on = on;
        }
    }

    protected final String baseUrl;
    protected final SSLSocketFactory pinnedFactory;
    protected final X509TrustManager pinTrust;
    protected final boolean pinned;
    protected final String certificatePin;
    protected volatile String cookie;
    private OkHttpClient socketClient;
    private volatile WebSocket events;
    private volatile boolean eventsReady;
    private Boolean directTextSupported;
    PikvmClient(String address, String fingerprint) throws Exception {
        this(address, fingerprint, false);
    }

    PikvmClient(String address, String fingerprint, boolean allowHttp) throws Exception {
        baseUrl = normalizeAddress(address, allowHttp);
        String supplied = fingerprint.trim();
        if (supplied.contains("=")) supplied = supplied.substring(supplied.lastIndexOf('=') + 1);
        String pin = supplied.replaceAll("[^A-Fa-f0-9]", "").toUpperCase(Locale.ROOT);
        if (!fingerprint.trim().isEmpty() && !pin.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException("Certificate fingerprint must be 64 hex digits (SHA-256).");
        }
        pinned = !pin.isEmpty();
        certificatePin = pin;
        if (pinned && baseUrl.startsWith("http://")) {
            throw new IllegalArgumentException("Certificate trust applies only to HTTPS connections.");
        }
        if (pinned) {
            SSLContext context = SSLContext.getInstance("TLS");
            pinTrust = new PinTrustManager(pin);
            context.init(null, new TrustManager[]{pinTrust}, new SecureRandom());
            pinnedFactory = context.getSocketFactory();
        } else {
            pinTrust = null;
            pinnedFactory = null;
        }
    }

    static String normalizeAddress(String address) throws URISyntaxException {
        return normalizeAddress(address, false);
    }

    static String normalizeAddress(String address, boolean allowHttp) throws URISyntaxException {
        String value = address.trim();
        if (!value.contains("://")) value = "https://" + value;
        URI uri = new URI(value);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if ((!https && !(allowHttp && "http".equalsIgnoreCase(uri.getScheme()))) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Enter a KVM host or HTTP(S) URL without a path.");
        }
        int port = uri.getPort() < 0 ? (https ? 443 : 80) : uri.getPort();
        return new URI(https ? "https" : "http", null, uri.getHost(), port, null, null, null).toString();
    }

    /** Reads the presented certificate without completing a trusted connection or sending credentials. */
    static String certificateFingerprint(String address) throws Exception {
        URI uri = new URI(normalizeAddress(address));
        CapturingTrustManager capture = new CapturingTrustManager();
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{capture}, new SecureRandom());
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 7000);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                    .createSocket(connection, uri.getHost(), uri.getPort(), true)) {
                socket.setSoTimeout(7000);
                try {
                    socket.startHandshake();
                } catch (SSLException expected) {
                    if (capture.certificate == null) throw expected;
                }
            }
        }
        if (capture.certificate == null) throw new IOException("The device did not present a certificate.");
        return fingerprint(capture.certificate);
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder value = new StringBuilder(95);
        for (byte b : digest) {
            if (value.length() > 0) value.append(':');
            value.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        }
        return value.toString();
    }

    private HttpsURLConnection connect(String path, String method, byte[] body, String contentType)
            throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(baseUrl + path).openConnection();
        if (pinned) {
            connection.setSSLSocketFactory(pinnedFactory);
            // An exact certificate pin authenticates the selected device even if its local cert lacks an IP SAN.
            connection.setHostnameVerifier((host, session) -> true);
        }
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(path.startsWith("/streamer/") ? 30000 : 12000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        String activeCookie = cookie;
        if (activeCookie != null) connection.setRequestProperty("Cookie", activeCookie);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        return connection;
    }

    private JSONObject request(String path, String method, byte[] body, String contentType)
            throws IOException, JSONException {
        HttpsURLConnection connection = connect(path, method, body, contentType);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : new String(readLimited(stream, 65536), StandardCharsets.UTF_8);
            JSONObject data = response.isEmpty() ? new JSONObject() : new JSONObject(response);
            if (status >= 400 || !data.optBoolean("ok", false)) {
                String reason = data.optJSONObject("result") == null ? "PiKVM request failed"
                        : data.optJSONObject("result").optString("error", "PiKVM request failed");
                throw new ApiException(status, reason + " (HTTP " + status + ")");
            }
            return data.optJSONObject("result") == null ? new JSONObject() : data.getJSONObject("result");
        } finally {
            connection.disconnect();
        }
    }

    static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("PiKVM response is too large.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    void login(String user, String password) throws IOException {
        byte[] form = ("user=" + encode(user) + "&passwd=" + encode(password))
                .getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection = connect("/api/auth/login", "POST", form,
                "application/x-www-form-urlencoded; charset=utf-8");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                String reason = "PiKVM login failed";
                try {
                    InputStream error = connection.getErrorStream();
                    if (error != null) {
                        JSONObject response = new JSONObject(new String(readLimited(error, 65536),
                                StandardCharsets.UTF_8));
                        JSONObject result = response.optJSONObject("result");
                        if (result != null) reason = result.optString("error", reason);
                    }
                } catch (IOException | JSONException ignored) {
                    // A proxy may return a non-JSON error page.
                }
                throw new ApiException(status, reason + " (HTTP " + status + ")");
            }
            String setCookie = connection.getHeaderField("Set-Cookie");
            if (setCookie == null || !setCookie.startsWith("auth_token=")) {
                throw new IOException("PiKVM did not return a login session.");
            }
            cookie = setCookie.split(";", 2)[0];
            directTextSupported = null;
        } finally {
            connection.disconnect();
        }
    }

    boolean hasNativeVideo() { return false; }

    WebSocket openNativeVideo(WebSocketListener listener) {
        throw new UnsupportedOperationException("PiKVM does not provide NanoKVM Direct video.");
    }

    static String encodeNativePassword(String password) throws Exception {
        byte[] salt = new byte[8];
        new SecureRandom().nextBytes(salt);
        byte[] secret = "nanokvm-sipeed-2024".getBytes(StandardCharsets.UTF_8);
        byte[] material = new byte[48], previous = new byte[0];
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        for (int offset = 0; offset < material.length; offset += 16) {
            md5.update(previous);
            md5.update(secret);
            md5.update(salt);
            previous = md5.digest();
            System.arraycopy(previous, 0, material, offset, 16);
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Arrays.copyOfRange(material, 0, 32), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(material, 32, 48)));
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] openssl = new byte[16 + encrypted.length];
        System.arraycopy("Salted__".getBytes(StandardCharsets.US_ASCII), 0, openssl, 0, 8);
        System.arraycopy(salt, 0, openssl, 8, 8);
        System.arraycopy(encrypted, 0, openssl, 16, encrypted.length);
        return encode(Base64.encodeToString(openssl, Base64.NO_WRAP));
    }

    PowerState powerState() throws IOException, JSONException {
        JSONObject state = request("/api/atx", "GET", null, null);
        return new PowerState(state.optBoolean("enabled"), state.getJSONObject("leds").optBoolean("power"));
    }

    JSONObject videoSettings() throws IOException, JSONException {
        return request("/api/streamer", "GET", null, null);
    }

    void setVideoParameter(String name, String value) throws IOException, JSONException {
        if (!"desired_fps".equals(name) && !"quality".equals(name)
                && !"resolution".equals(name)) {
            throw new IllegalArgumentException("Unsupported video parameter");
        }
        request("/api/streamer/set_params?" + name + "=" + encode(value),
                "POST", new byte[0], "text/plain");
    }

    void resetHid() throws IOException, JSONException {
        request("/api/hid/reset", "POST", new byte[0], "text/plain");
    }

    void setPower(boolean on) throws IOException, JSONException {
        request("/api/atx/power?action=" + (on ? "on" : "off"), "POST", new byte[0], "text/plain");
    }

    /** Ask PiKVM to send WOL through its configured GPIO output. */
    String wakeFromKvm(String mac, String gpioChannel) throws IOException, JSONException {
        JSONObject outputs = request("/api/gpio", "GET", null, null)
                .getJSONObject("model").getJSONObject("scheme").getJSONObject("outputs");
        String selected = gpioChannel.trim();
        if (selected.isEmpty()) {
            for (java.util.Iterator<String> keys = outputs.keys(); keys.hasNext(); ) {
                String channel = keys.next();
                JSONObject hw = outputs.getJSONObject(channel).optJSONObject("hw");
                String driver = hw == null ? "" : hw.optString("driver");
                if (!channel.toLowerCase(Locale.ROOT).contains("wol")
                        && !driver.toLowerCase(Locale.ROOT).contains("wol")) continue;
                if (!selected.isEmpty()) {
                    throw new IOException("PiKVM has several WOL outputs. Enter this PC's GPIO channel in WOL settings.");
                }
                selected = channel;
            }
            if (selected.isEmpty()) {
                throw new IOException("No PiKVM WOL output found. Configure WOL on PiKVM, then reconnect.");
            }
        }
        if (!outputs.has(selected)) {
            throw new IOException("PiKVM GPIO channel '" + selected + "' was not found.");
        }
        JSONObject output = outputs.getJSONObject(selected);
        JSONObject pulse = output.optJSONObject("pulse");
        String path;
        if (pulse != null && pulse.optDouble("delay") > 0) {
            path = "/api/gpio/pulse?channel=" + encode(selected) + "&wait=1";
        } else if (output.optBoolean("switch")) {
            path = "/api/gpio/switch?channel=" + encode(selected) + "&state=1&wait=1";
        } else {
            throw new IOException("PiKVM GPIO channel '" + selected + "' cannot be triggered.");
        }
        request(path, "POST", new byte[0], "text/plain");
        return "PiKVM sent the wake packet via " + selected + ".";
    }

    void sendKey(String code, List<String> modifiers) throws IOException, JSONException {
        if (eventsReady && events != null) {
            boolean sent = true;
            for (String modifier : modifiers) {
                if (!(sent = sendKeyState(modifier, true))) break;
            }
            if (sent) sent = sendKeyState(code, true) && sendKeyState(code, false);
            for (int n = modifiers.size() - 1; n >= 0; n--) {
                if (!sendKeyState(modifiers.get(n), false)) sent = false;
            }
            if (sent) return;
        }
        String path = modifiers.isEmpty()
                ? "/api/hid/events/send_key?key=" + encode(code)
                : "/api/hid/events/send_shortcut?keys=" + encode(String.join(",", modifiers) + "," + code);
        request(path, "POST", new byte[0], "text/plain");
    }

    void sendText(String text) throws IOException, JSONException {
        if (text.isEmpty()) return;
        if (text.length() <= 32 && eventsReady && events != null
                && isWsText(text) && canSendDirectText()) {
            boolean sent = true;
            for (int n = 0; n < text.length() && sent; n++) {
                char c = text.charAt(n);
                String code = keyForAscii(c);
                boolean shift = needsShift(c);
                if (shift) sent = sendKeyState("ShiftLeft", true);
                if (sent) sent = sendKeyState(code, true) && sendKeyState(code, false);
                if (shift && !sendKeyState("ShiftLeft", false)) sent = false;
            }
            if (sent) return;
        }
        request("/api/hid/print", "POST", text.getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");
    }

    private boolean canSendDirectText() {
        if (directTextSupported == null) {
            try {
                JSONObject result = request("/api/hid/keymaps", "GET", null, null);
                JSONObject keymaps = result.optJSONObject("keymaps");
                directTextSupported = keymaps != null && "en-us".equals(keymaps.optString("default"));
            } catch (IOException | JSONException error) {
                directTextSupported = false;
            }
        }
        return directTextSupported;
    }

    private boolean sendKeyState(String code, boolean down) throws JSONException {
        return sendInputEvent("key", new JSONObject().put("key", code).put("state", down));
    }

    private static boolean isWsText(String value) {
        for (int n = 0; n < value.length(); n++) {
            if (keyForAscii(value.charAt(n)) == null) return false;
        }
        return true;
    }

    private static String keyForAscii(char c) {
        if (c >= 'a' && c <= 'z') return "Key" + Character.toUpperCase(c);
        if (c >= 'A' && c <= 'Z') return "Key" + c;
        if (c >= '0' && c <= '9') return "Digit" + c;
        switch (c) {
            case ' ': return "Space";
            case '-': case '_': return "Minus";
            case '=': case '+': return "Equal";
            case '[': case '{': return "BracketLeft";
            case ']': case '}': return "BracketRight";
            case '\\': case '|': return "Backslash";
            case ';': case ':': return "Semicolon";
            case '\'': case '"': return "Quote";
            case '`': case '~': return "Backquote";
            case ',': case '<': return "Comma";
            case '.': case '>': return "Period";
            case '/': case '?': return "Slash";
            case '!': return "Digit1";
            case '@': return "Digit2";
            case '#': return "Digit3";
            case '$': return "Digit4";
            case '%': return "Digit5";
            case '^': return "Digit6";
            case '&': return "Digit7";
            case '*': return "Digit8";
            case '(': return "Digit9";
            case ')': return "Digit0";
            default: return null;
        }
    }

    private static boolean needsShift(char c) {
        return (c >= 'A' && c <= 'Z') || "!@#$%^&*()_+{}|:\"~<>?".indexOf(c) >= 0;
    }

    void moveMouse(float x, float y) throws IOException, JSONException {
        int toX = Math.max(-32768, Math.min(32767, Math.round(x * 65535) - 32768));
        int toY = Math.max(-32768, Math.min(32767, Math.round(y * 65535) - 32768));
        JSONObject point = new JSONObject().put("x", toX).put("y", toY);
        if (sendInputEvent("mouse_move", new JSONObject().put("to", point))) return;
        request("/api/hid/events/send_mouse_move?to_x=" + toX + "&to_y=" + toY,
                "POST", new byte[0], "text/plain");
    }

    void mouseClick(String button, int count) throws IOException, JSONException {
        for (int n = 0; n < count; n++) {
            if (!eventsReady || events == null) {
                request("/api/hid/events/send_mouse_button?button=" + encode(button),
                        "POST", new byte[0], "text/plain");
                continue;
            }
            mouseButton(button, true);
            mouseButton(button, false);
        }
    }

    void mouseButton(String button, boolean down) throws IOException, JSONException {
        if (sendInputEvent("mouse_button", new JSONObject().put("button", button).put("state", down))) return;
        request("/api/hid/events/send_mouse_button?button=" + encode(button)
                        + "&state=" + down, "POST", new byte[0], "text/plain");
    }

    void mouseWheel(int steps) throws IOException, JSONException {
        int value = Math.max(-127, Math.min(127, steps));
        JSONObject delta = new JSONObject().put("x", 0).put("y", value);
        if (sendInputEvent("mouse_wheel", new JSONObject().put("delta", delta).put("squash", true))) return;
        request("/api/hid/events/send_mouse_wheel?delta_x=0&delta_y=" + value,
                "POST", new byte[0], "text/plain");
    }

    private boolean sendInputEvent(String kind, JSONObject event) throws JSONException {
        WebSocket socket = events;
        if (!eventsReady || socket == null) return false;
        return socket.send(new JSONObject().put("event_type", kind).put("event", event).toString());
    }

    HttpURLConnection openVideo() throws IOException {
        HttpsURLConnection connection = connect("/streamer/stream", "GET", null, null);
        connection.setRequestProperty("Accept", "multipart/x-mixed-replace");
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new ApiException(status, "Video unavailable (HTTP " + status + ")");
        }
        return connection;
    }

    synchronized void ensureEvents() {
        if (cookie == null || events != null) return;
        if (socketClient == null) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS);
            if (pinned) {
                builder.sslSocketFactory(pinnedFactory, pinTrust);
                builder.hostnameVerifier((host, session) -> true);
            }
            socketClient = builder.build();
        }
        Request request = new Request.Builder()
                .url(baseUrl + "/api/ws")
                .header("Cookie", cookie)
                .build();
        eventsReady = false;
        events = socketClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String message) {
                synchronized (PikvmClient.this) {
                    if (events == socket) eventsReady = true;
                }
            }
            @Override public void onClosing(WebSocket socket, int code, String reason) {
                synchronized (PikvmClient.this) {
                    if (events == socket) eventsReady = false;
                }
            }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                clearEvents(socket);
            }
            @Override public void onClosed(WebSocket socket, int code, String reason) {
                clearEvents(socket);
            }
        });
    }

    private synchronized void clearEvents(WebSocket socket) {
        if (events == socket) {
            eventsReady = false;
            events = null;
        }
    }

    synchronized void stopEvents() {
        WebSocket active = events;
        eventsReady = false;
        events = null;
        if (active != null) active.close(1000, "App paused");
    }

    void logout() {
        stopEvents();
        try {
            request("/api/auth/logout", "POST", new byte[0], "text/plain");
        } catch (Exception ignored) {
            // The local session is discarded even when the network is unavailable.
        } finally {
            cookie = null;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class PinTrustManager implements X509TrustManager {
        private final String expected;
        PinTrustManager(String expected) { this.expected = expected; }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("No server certificate.");
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                StringBuilder actual = new StringBuilder(64);
                for (byte b : digest) actual.append(String.format(Locale.ROOT, "%02X", b & 0xff));
                if (!MessageDigest.isEqual(actual.toString().getBytes(StandardCharsets.US_ASCII),
                        expected.getBytes(StandardCharsets.US_ASCII))) {
                    throw new CertificateException("Certificate fingerprint does not match.");
                }
            } catch (NoSuchAlgorithmException e) {
                throw new CertificateException(e);
            }
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static final class CapturingTrustManager implements X509TrustManager {
        X509Certificate certificate;
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain != null && chain.length > 0) certificate = chain[0];
            throw new CertificateException("Certificate requires confirmation");
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
