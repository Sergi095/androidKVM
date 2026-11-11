package org.androidkvm.app;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.function.Supplier;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** An authenticated NanoKVM PTY with xterm.js bundled inside a local WebView. */
final class NanoTerminalPanel extends LinearLayout {
    private static final String ASSET_URL = "file:///android_asset/terminal/index.html";
    private final NanoKvmClient client;
    private final String serialCommand;
    private final Supplier<List<String>> consumeModifiers;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView status;
    private final WebView terminal;
    private volatile WebSocket socket;
    private volatile boolean connected;
    private volatile boolean stopped;
    private volatile int rows = 24, cols = 80;

    NanoTerminalPanel(Context context, NanoKvmClient client, String serialCommand,
                      Supplier<List<String>> consumeModifiers) {
        super(context);
        this.client = client;
        this.serialCommand = serialCommand;
        this.consumeModifiers = consumeModifiers;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);

        status = label("Connecting…", 12, R.color.app_muted);
        status.setPadding(dp(8), dp(3), dp(8), dp(3));
        addView(status);

        terminal = new WebView(context);
        terminal.setBackgroundColor(Color.BLACK);
        terminal.setFocusable(true);
        terminal.setFocusableInTouchMode(true);
        WebSettings settings = terminal.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        terminal.addJavascriptInterface(new InputBridge(), "AndroidTerminal");
        terminal.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().startsWith("file:///android_asset/terminal/")) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!stopped && ASSET_URL.equals(url)) {
                    openSocket();
                    handler.postDelayed(NanoTerminalPanel.this::showKeyboard, 180);
                }
            }
        });
        addView(terminal, new LayoutParams(-1, 0, 1));
        terminal.loadUrl(ASSET_URL);
    }

    private void openSocket() {
        if (socket != null || stopped) return;
        socket = client.openTerminal(new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (stopped) { webSocket.close(1000, "Terminal closed"); return; }
                connected = true;
                sendSize(webSocket, rows, cols);
                handler.post(() -> {
                    if (!stopped) {
                        status.setText("Connected");
                        status.setVisibility(View.GONE);
                        terminal.evaluateJavascript("window.terminalFocus()", null);
                    }
                });
                if (serialCommand != null) handler.postDelayed(() -> {
                    if (!stopped && connected && socket == webSocket) webSocket.send(serialCommand + "\r");
                }, 350);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                handler.post(() -> {
                    if (!stopped && socket == webSocket) terminal.evaluateJavascript(
                            "window.terminalReceive('" + encoded + "')", null);
                });
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handler.post(() -> {
                    if (!stopped && socket == webSocket) terminal.evaluateJavascript(
                            "window.terminalText(" + org.json.JSONObject.quote(text) + ")", null);
                });
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                connected = false;
                String reason = response == null ? error.getMessage()
                        : "HTTP " + response.code() + (response.code() == 403 ? " · Terminal access denied" : "");
                handler.post(() -> {
                    if (!stopped) {
                        status.setVisibility(View.VISIBLE);
                        status.setText("Terminal unavailable: " + reason);
                    }
                });
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                handler.post(() -> {
                    if (!stopped) {
                        status.setVisibility(View.VISIBLE);
                        status.setText("Terminal disconnected" + (code == 4401
                                ? " · Sign in again" : code == 4403 ? " · Access denied" : ""));
                    }
                });
            }
        });
    }

    private void sendRaw(String value) {
        WebSocket active = socket;
        if (!stopped && connected && active != null) active.send(value);
    }

    private static void sendSize(WebSocket socket, int rows, int cols) {
        socket.send(ByteString.encodeUtf8("{\"rows\":" + rows + ",\"cols\":" + cols + "}"));
    }

    private final class InputBridge {
        @JavascriptInterface public void send(String value) {
            if (value != null && value.length() <= 4096)
                handler.post(() -> sendKeyboardText(value));
        }

        @JavascriptInterface public void resize(int nextRows, int nextCols) {
            if (nextRows < 2 || nextRows > 200 || nextCols < 2 || nextCols > 400) return;
            rows = nextRows;
            cols = nextCols;
            WebSocket active = socket;
            if (!stopped && connected && active != null) sendSize(active, rows, cols);
        }
    }

    void showKeyboard() {
        if (stopped) return;
        terminal.requestFocus();
        terminal.evaluateJavascript("window.terminalFocus()", null);
        InputMethodManager input = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) handler.postDelayed(() -> input.showSoftInput(terminal,
                InputMethodManager.SHOW_IMPLICIT), 120);
    }

    private void sendKeyboardText(String value) {
        if (value.isEmpty()) return;
        sendRaw(applyModifiers(value, consumeModifiers.get()));
    }

    void sendKey(String code, List<String> modifiers) {
        String value = terminalSequence(code);
        if (value != null) sendRaw(applyModifiers(value, modifiers));
    }

    private static String applyModifiers(String value, List<String> modifiers) {
        boolean controlPressed = modifiers.contains("ControlLeft");
        boolean altPressed = modifiers.contains("AltLeft");
        if (controlPressed && value.length() == 1) {
            char character = Character.toUpperCase(value.charAt(0));
            if (character >= '@' && character <= '_') value = String.valueOf((char) (character - '@'));
            else if (character == '/') value = "\u001f";
            else if (character == '?') value = "\u007f";
        } else if ((controlPressed || altPressed) && value.startsWith("\u001b")) {
            int modifier = 1 + (controlPressed ? 4 : 0) + (altPressed ? 2 : 0);
            if (value.startsWith("\u001bO") && value.length() == 3)
                return "\u001b[1;" + modifier + value.charAt(2);
            if (value.startsWith("\u001b[") && value.endsWith("~"))
                return value.substring(0, value.length() - 1) + ";" + modifier + "~";
            if (value.startsWith("\u001b[") && value.length() == 3)
                return "\u001b[1;" + modifier + value.charAt(2);
        }
        return altPressed ? "\u001b" + value : value;
    }

    private static String terminalSequence(String code) {
        switch (code) {
            case "Escape": return "\u001b";
            case "Slash": return "/";
            case "Minus": return "-";
            case "Home": return "\u001b[H";
            case "ArrowUp": return "\u001b[A";
            case "End": return "\u001b[F";
            case "PageUp": return "\u001b[5~";
            case "Tab": return "\t";
            case "ArrowLeft": return "\u001b[D";
            case "ArrowDown": return "\u001b[B";
            case "ArrowRight": return "\u001b[C";
            case "PageDown": return "\u001b[6~";
            case "Delete": return "\u001b[3~";
            case "F1": return "\u001bOP";
            case "F2": return "\u001bOQ";
            case "F3": return "\u001bOR";
            case "F4": return "\u001bOS";
            case "F5": return "\u001b[15~";
            case "F6": return "\u001b[17~";
            case "F7": return "\u001b[18~";
            case "F8": return "\u001b[19~";
            case "F9": return "\u001b[20~";
            case "F10": return "\u001b[21~";
            case "F11": return "\u001b[23~";
            case "F12": return "\u001b[24~";
            default: return null;
        }
    }

    void close() {
        if (stopped) return;
        stopped = true;
        WebSocket active = socket;
        connected = false;
        socket = null;
        if (active != null) {
            if (serialCommand != null) active.send("\u0001\u0018");
            active.close(1000, "Terminal closed");
        }
        terminal.removeJavascriptInterface("AndroidTerminal");
        removeView(terminal);
        terminal.destroy();
        handler.removeCallbacksAndMessages(null);
    }

    private TextView label(String text, int size, int colorId) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color(colorId));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int color(int id) { return ContextCompat.getColor(getContext(), id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
