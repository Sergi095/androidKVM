package org.androidkvm.app;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
final class PikvmClient {
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

    private final String baseUrl;
    private final SSLSocketFactory pinnedFactory;
    private final X509TrustManager pinTrust;
    private final boolean pinned;
    private volatile String cookie;
    private OkHttpClient socketClient;
    private volatile WebSocket events;

    PikvmClient(String address, String fingerprint) throws Exception {
        baseUrl = normalizeAddress(address);
        String supplied = fingerprint.trim();
        if (supplied.contains("=")) supplied = supplied.substring(supplied.lastIndexOf('=') + 1);
        String pin = supplied.replaceAll("[^A-Fa-f0-9]", "").toUpperCase(Locale.ROOT);
        if (!fingerprint.trim().isEmpty() && !pin.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException("Certificate fingerprint must be 64 hex digits (SHA-256).");
        }
        pinned = !pin.isEmpty();
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
        String value = address.trim();
        if (!value.contains("://")) value = "https://" + value;
        URI uri = new URI(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Enter a PiKVM host or HTTPS URL without a path.");
        }
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        return new URI("https", null, uri.getHost(), port, null, null, null).toString();
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

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
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
            if (status != 200) throw new ApiException(status, "Login failed (HTTP " + status + ")");
            String setCookie = connection.getHeaderField("Set-Cookie");
            if (setCookie == null || !setCookie.startsWith("auth_token=")) {
                throw new IOException("PiKVM did not return a login session.");
            }
            cookie = setCookie.split(";", 2)[0];
        } finally {
            connection.disconnect();
        }
    }

    PowerState powerState() throws IOException, JSONException {
        JSONObject state = request("/api/atx", "GET", null, null);
        return new PowerState(state.optBoolean("enabled"), state.getJSONObject("leds").optBoolean("power"));
    }

    void setPower(boolean on) throws IOException, JSONException {
        request("/api/atx/power?action=" + (on ? "on" : "off"), "POST", new byte[0], "text/plain");
    }

    void sendKey(String code, List<String> modifiers) throws IOException, JSONException {
        String path = modifiers.isEmpty()
                ? "/api/hid/events/send_key?key=" + encode(code)
                : "/api/hid/events/send_shortcut?keys=" + encode(String.join(",", modifiers) + "," + code);
        request(path, "POST", new byte[0], "text/plain");
    }

    void sendText(String text) throws IOException, JSONException {
        if (!text.isEmpty()) {
            request("/api/hid/print", "POST", text.getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8");
        }
    }

    void moveMouse(float x, float y) throws IOException, JSONException {
        int toX = Math.max(-32768, Math.min(32767, Math.round(x * 65535) - 32768));
        int toY = Math.max(-32768, Math.min(32767, Math.round(y * 65535) - 32768));
        request("/api/hid/events/send_mouse_move?to_x=" + toX + "&to_y=" + toY,
                "POST", new byte[0], "text/plain");
    }

    void mouseClick(String button, int count) throws IOException, JSONException {
        for (int n = 0; n < count; n++) {
            request("/api/hid/events/send_mouse_button?button=" + encode(button),
                    "POST", new byte[0], "text/plain");
        }
    }

    void mouseButton(String button, boolean down) throws IOException, JSONException {
        request("/api/hid/events/send_mouse_button?button=" + encode(button)
                        + "&state=" + down, "POST", new byte[0], "text/plain");
    }

    void mouseWheel(int steps) throws IOException, JSONException {
        request("/api/hid/events/send_mouse_wheel?delta_y=" + Math.max(-127, Math.min(127, steps)),
                "POST", new byte[0], "text/plain");
    }

    HttpsURLConnection openVideo() throws IOException {
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
        events = socketClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                clearEvents(socket);
            }
            @Override public void onClosed(WebSocket socket, int code, String reason) {
                clearEvents(socket);
            }
        });
    }

    private synchronized void clearEvents(WebSocket socket) {
        if (events == socket) events = null;
    }

    synchronized void stopEvents() {
        WebSocket active = events;
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
