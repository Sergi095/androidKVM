package org.androidkvm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Minimal native Android console for PiKVM-compatible devices. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 24, 40);
    private static final int CONSOLE_BG = Color.BLACK;
    private static final int PANEL = Color.rgb(27, 39, 59);
    private static final int TEXT = Color.rgb(245, 249, 251);
    private static final int MUTED = Color.rgb(170, 188, 200);
    private static final int ACCENT = Color.rgb(76, 205, 184);
    private static final int DANGER = Color.rgb(182, 77, 82);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService mouseIo = Executors.newSingleThreadExecutor();
    private final StringBuilder pendingText = new StringBuilder();
    private final Runnable textFlush = this::flushTextNow;
    private final AtomicReference<PointF> pendingMove = new AtomicReference<>();
    private final AtomicBoolean moveQueued = new AtomicBoolean();
    private final LinkedHashSet<String> modifiers = new LinkedHashSet<>();
    private final List<Button> modifierButtons = new ArrayList<>();
    private PikvmClient client;
    private MjpegReader videoReader;
    private Bitmap currentFrame;
    private ScrollView loginPage;
    private LinearLayout consolePage;
    private LinearLayout loginPanel;
    private EditText addressInput, userInput, passwordInput, pinInput;
    private RemoteScreen screen;
    private RemoteInput remoteInput;
    private TextView message, videoStatus, powerStatus;
    private Button loginButton, onButton, offButton;

    private final Runnable powerPoll = new Runnable() {
        @Override public void run() {
            if (client == null) return;
            client.ensureEvents();
            refreshPower();
            handler.postDelayed(this, 5000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
    }

    @Override public void onStart() {
        super.onStart();
        if (client != null) startVideo(client);
    }

    @Override public void onStop() {
        stopVideo();
        if (client != null) client.stopEvents();
        super.onStop();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(powerPoll);
        stopVideo();
        io.shutdownNow();
        mouseIo.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        loginPage = new ScrollView(this);
        loginPage.setFillViewport(true);
        loginPage.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).top : insets.getSystemWindowInsetTop();
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom : insets.getSystemWindowInsetBottom();
            view.setPadding(dp(16), dp(12) + top, dp(16), dp(24) + bottom);
            return insets;
        });
        loginPage.addView(root);
        setContentView(loginPage);

        TextView title = text("androidKVM", 25, TEXT);
        title.setTypeface(null, 1);
        root.addView(title);
        root.addView(text("PiKVM-compatible console", 14, MUTED));

        loginPanel = panel();
        root.addView(loginPanel, spaced());
        loginPanel.addView(text("Connect", 20, TEXT));
        loginPanel.addView(text("Use your KVM web account.", 13, MUTED));
        addressInput = field("PiKVM address, e.g. pikvm.local", false);
        userInput = field("Username", false);
        passwordInput = field("Password", true);
        pinInput = field("SHA-256 certificate fingerprint (optional)", false);
        addressInput.setText(getPreferences(0).getString("address", ""));
        userInput.setText(getPreferences(0).getString("user", ""));
        pinInput.setText(getPreferences(0).getString("pin", ""));
        for (EditText input : new EditText[]{addressInput, userInput, passwordInput, pinInput}) {
            loginPanel.addView(input, spaced());
        }
        loginPanel.addView(text("If Android does not trust the certificate, you can inspect its fingerprint before connecting. Passwords are not saved. For 2FA, append the current code to the password.", 12, MUTED), spaced());
        loginButton = button("Connect", ACCENT, BG);
        loginButton.setOnClickListener(view -> login());
        loginPanel.addView(loginButton, spaced());

        message = text("", 13, MUTED);
        message.setMinHeight(dp(30));
        root.addView(message, spaced());

        consolePage = column();
        consolePage.setBackgroundColor(CONSOLE_BG);
        consolePage.setPadding(0, 0, 0, 0);
        consolePage.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).top : insets.getSystemWindowInsetTop();
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom : insets.getSystemWindowInsetBottom();
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        LinearLayout toolbar = row();
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        toolbar.setBackgroundColor(CONSOLE_BG);
        TextView name = text("androidKVM", 14, TEXT);
        name.setTypeface(null, 1);
        toolbar.addView(name, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button keyboard = compactButton("⌨");
        keyboard.setContentDescription("Show or hide Android keyboard");
        keyboard.setOnClickListener(view -> toggleKeyboard());
        toolbar.addView(keyboard, new LinearLayout.LayoutParams(dp(48), dp(40)));
        Button power = compactButton("Power");
        power.setOnClickListener(view -> showPowerDialog());
        toolbar.addView(power, new LinearLayout.LayoutParams(dp(68), dp(40)));
        Button disconnect = compactButton("×");
        disconnect.setContentDescription("Disconnect");
        disconnect.setOnClickListener(view -> logout());
        toolbar.addView(disconnect, new LinearLayout.LayoutParams(dp(42), dp(40)));
        consolePage.addView(toolbar);

        screen = new RemoteScreen(this);
        screen.setBackgroundColor(Color.BLACK);
        screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.setContentDescription("Remote screen. Touch to move pointer; tap to click.");
        consolePage.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));

        videoStatus = text("Connecting to video…", 11, MUTED);
        videoStatus.setPadding(dp(8), 0, dp(8), 0);
        consolePage.addView(videoStatus, new LinearLayout.LayoutParams(-1, dp(18)));

        LinearLayout extraKeys = column();
        extraKeys.setBackgroundColor(CONSOLE_BG);
        extraKeys.addView(compactKeyRow(new String[][]{
                {"ESC", "Escape"}, {"/", "Slash"}, {"−", "Minus"},
                {"HOME", "Home"}, {"↑", "ArrowUp"}, {"END", "End"}, {"PGUP", "PageUp"}
        }));
        extraKeys.addView(compactKeyRow(new String[][]{
                {"TAB", "Tab"}, {"CTRL", "ControlLeft"}, {"ALT", "AltLeft"},
                {"←", "ArrowLeft"}, {"↓", "ArrowDown"}, {"→", "ArrowRight"}, {"PGDN", "PageDown"}
        }));
        String[][] functionKeys = new String[12][2];
        for (int n = 1; n <= 12; n++) {
            functionKeys[n - 1][0] = "F" + n;
            functionKeys[n - 1][1] = "F" + n;
        }
        extraKeys.addView(keyRow(functionKeys));
        consolePage.addView(extraKeys);

        remoteInput = new RemoteInput(this);
        consolePage.addView(remoteInput, new LinearLayout.LayoutParams(dp(1), dp(1)));
    }

    private HorizontalScrollView keyRow(String[][] keys) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = row();
        for (String[] item : keys) {
            String label = item[0], code = item[1];
            Button key = compactButton(label);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(54), dp(43));
            row.addView(key, params);
            bindKey(key, label, code);
        }
        scroll.addView(row);
        return scroll;
    }

    private LinearLayout compactKeyRow(String[][] keys) {
        LinearLayout row = row();
        for (String[] item : keys) {
            String label = item[0], code = item[1];
            Button key = compactButton(label);
            row.addView(key, new LinearLayout.LayoutParams(0, dp(43), 1));
            bindKey(key, label, code);
        }
        return row;
    }

    private Button compactButton(String label) {
        Button button = button(label, CONSOLE_BG, TEXT);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setTextSize(label.length() > 4 ? 10 : 12);
        button.setFocusable(false);
        button.setBackgroundColor(CONSOLE_BG);
        return button;
    }

    private void bindKey(Button key, String label, String code) {
        if (code.equals("ControlLeft") || code.equals("AltLeft") || code.equals("ShiftLeft")) {
            modifierButtons.add(key);
            key.setTag(new String[]{label, code});
            key.setOnClickListener(view -> toggleModifier(code));
        } else {
            key.setOnClickListener(view -> sendKey(code));
        }
    }

    private void showPowerDialog() {
        LinearLayout content = panel();
        powerStatus = text("Checking…", 14, MUTED);
        content.addView(powerStatus);
        LinearLayout row = row();
        onButton = button("Turn on", ACCENT, BG);
        offButton = button("Shut down", DANGER, TEXT);
        row.addView(onButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams offLayout = new LinearLayout.LayoutParams(0, dp(48), 1);
        offLayout.leftMargin = dp(8);
        row.addView(offButton, offLayout);
        content.addView(row, spaced());
        onButton.setOnClickListener(view -> setPower(true));
        offButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Shut down remote computer?")
                .setMessage("This presses its power button briefly.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Shut down", (dialog, which) -> setPower(false))
                .show());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Power")
                .setView(content)
                .setNegativeButton("Close", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            powerStatus = null;
            onButton = null;
            offButton = null;
        });
        dialog.show();
        refreshPower();
    }

    private void toggleKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard == null) return;
        Rect visible = new Rect();
        consolePage.getWindowVisibleDisplayFrame(visible);
        boolean shown = consolePage.getRootView().getHeight() - visible.bottom > dp(140);
        if (shown) {
            keyboard.hideSoftInputFromWindow(remoteInput.getWindowToken(), 0);
        } else {
            remoteInput.requestFocus();
            keyboard.restartInput(remoteInput);
            keyboard.showSoftInput(remoteInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void resetKeyboardComposition() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.restartInput(remoteInput);
    }

    private void login() {
        String address = addressInput.getText().toString().trim();
        String user = userInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String pin = pinInput.getText().toString().trim();
        if (address.isEmpty() || user.isEmpty() || password.isEmpty()) {
            showMessage("Enter the KVM address, username, and password.");
            return;
        }
        loginButton.setEnabled(false);
        showMessage("Connecting…");
        io.execute(() -> {
            try {
                PikvmClient next = new PikvmClient(address, pin);
                next.login(user, password);
                runOnUiThread(() -> {
                    client = next;
                    getPreferences(0).edit().putString("address", address).putString("user", user)
                            .putString("pin", pin).apply();
                    passwordInput.setText("");
                    setContentView(consolePage);
                    loginButton.setEnabled(true);
                    showMessage("Connected");
                    startVideo(next);
                    handler.removeCallbacks(powerPoll);
                    handler.post(powerPoll);
                    remoteInput.postDelayed(() -> {
                        remoteInput.requestFocus();
                        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (keyboard != null) keyboard.showSoftInput(remoteInput, InputMethodManager.SHOW_IMPLICIT);
                    }, 150);
                });
            } catch (Exception error) {
                if (certificateError(error)) {
                    if (!pin.isEmpty()) {
                        runOnUiThread(() -> {
                            loginButton.setEnabled(true);
                            showMessage("The saved certificate fingerprint did not validate. Check it before reconnecting.");
                        });
                        return;
                    }
                    try {
                        String fingerprint = PikvmClient.certificateFingerprint(address);
                        runOnUiThread(() -> showCertificateDialog(address, fingerprint));
                        return;
                    } catch (Exception inspectionError) {
                        // Show the original connection failure below.
                    }
                }
                runOnUiThread(() -> {
                    loginButton.setEnabled(true);
                    showMessage(error.getMessage());
                });
            }
        });
    }

    private static boolean certificateError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException
                    || cause instanceof java.security.cert.CertificateException) return true;
        }
        return false;
    }

    private void showCertificateDialog(String address, String fingerprint) {
        loginButton.setEnabled(true);
        showMessage("Certificate confirmation required");
        new AlertDialog.Builder(this)
                .setTitle("Verify KVM certificate")
                .setMessage("Android does not trust the certificate presented by " + address
                        + ". Compare this SHA-256 fingerprint with your KVM before continuing:\n\n"
                        + fingerprint)
                .setNegativeButton("Cancel", (dialog, which) -> showMessage("Connection cancelled"))
                .setPositiveButton("Trust and connect", (dialog, which) -> {
                    pinInput.setText(fingerprint);
                    login();
                })
                .show();
    }

    private void startVideo(PikvmClient active) {
        stopVideo();
        active.ensureEvents();
        videoReader = new MjpegReader(active, new MjpegReader.Listener() {
            @Override public void onFrame(Bitmap frame) {
                runOnUiThread(() -> {
                    if (client == active) {
                        currentFrame = frame;
                        screen.setImageBitmap(frame);
                    }
                });
            }
            @Override public void onStatus(String status) {
                runOnUiThread(() -> { if (client == active) videoStatus.setText(status); });
            }
        });
        videoReader.start();
    }

    private void stopVideo() {
        if (videoReader != null) {
            videoReader.stop();
            videoReader = null;
        }
    }

    private void refreshPower() {
        PikvmClient active = client;
        if (active == null) return;
        io.execute(() -> {
            try {
                PikvmClient.PowerState state = active.powerState();
                runOnUiThread(() -> {
                    if (client != active) return;
                    if (powerStatus != null) powerStatus.setText(state.enabled
                            ? (state.on ? "Power LED reports on" : "Power LED reports off")
                            : "ATX control unavailable");
                    if (onButton != null && offButton != null) {
                        onButton.setEnabled(state.enabled && !state.on);
                        offButton.setEnabled(state.enabled && state.on);
                        onButton.setAlpha(onButton.isEnabled() ? 1f : 0.45f);
                        offButton.setAlpha(offButton.isEnabled() ? 1f : 0.45f);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (client == active && powerStatus != null) powerStatus.setText(error.getMessage());
                });
            }
        });
    }

    private void setPower(boolean on) {
        PikvmClient active = client;
        if (active == null) return;
        io.execute(() -> {
            try {
                active.setPower(on);
                runOnUiThread(() -> { showMessage(on ? "Power-on sent" : "Shutdown sent"); refreshPower(); });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(error.getMessage()));
            }
        });
    }

    private void toggleModifier(String code) {
        if (modifiers.contains(code)) modifiers.remove(code);
        else modifiers.add(code);
        updateModifierButtons();
    }

    private void updateModifierButtons() {
        for (Button button : modifierButtons) {
            String[] tag = (String[]) button.getTag();
            boolean active = modifiers.contains(tag[1]);
            button.setText(tag[0]);
            if (active) button.setBackground(rounded(ACCENT));
            else button.setBackgroundColor(CONSOLE_BG);
            button.setTextColor(active ? BG : TEXT);
        }
    }

    private void sendKey(String code) {
        PikvmClient active = client;
        if (active == null) return;
        flushTextNow();
        List<String> held = new ArrayList<>(modifiers);
        modifiers.clear();
        updateModifierButtons();
        io.execute(() -> {
            try { active.sendKey(code, held); }
            catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
        });
    }

    private void sendRemoteText(String value) {
        if (client == null || value == null || value.isEmpty()) return;
        if (value.length() == 1 && !modifiers.isEmpty()) {
            String code = keyForCharacter(value.charAt(0));
            if (code != null) {
                sendKey(code);
                return;
            }
        }
        int start = 0;
        for (int n = 0; n < value.length(); n++) {
            if (value.charAt(n) == '\n') {
                sendTextChunk(value.substring(start, n));
                sendKey("Enter");
                start = n + 1;
            }
        }
        sendTextChunk(value.substring(start));
    }

    private void sendTextChunk(String value) {
        if (client == null || value.isEmpty()) return;
        pendingText.append(value);
        handler.removeCallbacks(textFlush);
        handler.postDelayed(textFlush, 120);
    }

    private void flushTextNow() {
        handler.removeCallbacks(textFlush);
        PikvmClient active = client;
        if (active == null || pendingText.length() == 0) {
            pendingText.setLength(0);
            return;
        }
        String value = pendingText.toString();
        pendingText.setLength(0);
        for (int start = 0; start < value.length(); start += 400) {
            String chunk = value.substring(start, Math.min(start + 400, value.length()));
            io.execute(() -> {
                try { active.sendText(chunk); }
                catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
            });
        }
    }

    private String keyForCharacter(char value) {
        if (value >= 'a' && value <= 'z') return "Key" + Character.toUpperCase(value);
        if (value >= 'A' && value <= 'Z') return "Key" + value;
        if (value >= '0' && value <= '9') return "Digit" + value;
        switch (value) {
            case ' ': return "Space";
            case '/': return "Slash";
            case '-': return "Minus";
            case '.': return "Period";
            case ',': return "Comma";
            default: return null;
        }
    }

    private PointF screenPoint(float x, float y) {
        Bitmap frame = currentFrame;
        if (frame == null || screen.getWidth() == 0 || screen.getHeight() == 0) return null;
        float scale = Math.min((float) screen.getWidth() / frame.getWidth(),
                (float) screen.getHeight() / frame.getHeight());
        float width = frame.getWidth() * scale, height = frame.getHeight() * scale;
        float left = (screen.getWidth() - width) / 2, top = (screen.getHeight() - height) / 2;
        if (x < left || x > left + width || y < top || y > top + height) return null;
        return new PointF((x - left) / width, (y - top) / height);
    }

    private void queueMouseMove(float x, float y) {
        PointF point = screenPoint(x, y);
        if (point == null || client == null) return;
        pendingMove.set(point);
        if (moveQueued.compareAndSet(false, true)) mouseIo.execute(this::drainMouseMoves);
    }

    private void drainMouseMoves() {
        try {
            PointF point;
            while ((point = pendingMove.getAndSet(null)) != null) {
                PikvmClient active = client;
                if (active != null) active.moveMouse(point.x, point.y);
            }
        } catch (Exception error) {
            runOnUiThread(() -> showMessage(error.getMessage()));
        } finally {
            moveQueued.set(false);
            if (pendingMove.get() != null && moveQueued.compareAndSet(false, true)) {
                mouseIo.execute(this::drainMouseMoves);
            }
        }
    }

    private void mouseClick(String button, int count) {
        PikvmClient active = client;
        if (active == null) return;
        mouseIo.execute(() -> {
            try { active.mouseClick(button, count); }
            catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
        });
    }

    private void mouseButton(String button, boolean down) {
        PikvmClient active = client;
        if (active == null) return;
        mouseIo.execute(() -> {
            try { active.mouseButton(button, down); }
            catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
        });
    }

    private void mouseWheel(int steps) {
        PikvmClient active = client;
        if (active == null || steps == 0) return;
        mouseIo.execute(() -> {
            try { active.mouseWheel(steps); }
            catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
        });
    }

    private final class RemoteInput extends View {
        RemoteInput(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override public boolean onCheckIsTextEditor() { return true; }

        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
            return new BaseInputConnection(this, false) {
                private String composing = "";

                private void updateComposing(String next) {
                    int common = 0;
                    while (common < composing.length() && common < next.length()
                            && composing.charAt(common) == next.charAt(common)) common++;
                    for (int n = common; n < composing.length(); n++) sendKey("Backspace");
                    sendRemoteText(next.substring(common));
                    composing = next;
                }

                @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                    String value = text == null ? "" : text.toString();
                    if (!composing.isEmpty()) {
                        if (value.length() == 1 && !Character.isLetterOrDigit(value.charAt(0))) {
                            composing = "";
                            sendRemoteText(value);
                        } else {
                            updateComposing(value);
                            composing = "";
                        }
                    } else sendRemoteText(value);
                    return true;
                }
                @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    updateComposing(text == null ? "" : text.toString());
                    return true;
                }
                @Override public boolean finishComposingText() {
                    composing = "";
                    return true;
                }
                @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    for (int n = 0; n < beforeLength; n++) sendKey("Backspace");
                    for (int n = 0; n < afterLength; n++) sendKey("Delete");
                    if (beforeLength > 0 && !composing.isEmpty()) {
                        composing = composing.substring(0, Math.max(0, composing.length() - beforeLength));
                    }
                    return true;
                }
                @Override public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) sendKey("Backspace");
                    else if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) sendKey("Enter");
                    else if (event.getKeyCode() == KeyEvent.KEYCODE_TAB) sendKey("Tab");
                    else if (event.getUnicodeChar() != 0) sendRemoteText(String.valueOf((char) event.getUnicodeChar()));
                    return true;
                }
                @Override public boolean performEditorAction(int actionCode) {
                    sendKey("Enter");
                    return true;
                }
            };
        }
    }

    private final class RemoteScreen extends ImageView {
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private Runnable longPress;
        private Runnable pendingClick;
        private float startX, startY, lastTapX, lastTapY, scrollY;
        private long lastTapAt;
        private boolean moved, multiTouch, scrolled, dragging;

        RemoteScreen(Context context) { super(context); }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    moved = multiTouch = scrolled = dragging = false;
                    resetKeyboardComposition();
                    queueMouseMove(startX, startY);
                    longPress = () -> {
                        if (!moved && !multiTouch && client != null) {
                            dragging = true;
                            mouseButton("left", true);
                        }
                    };
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelRemoteLongPress();
                    multiTouch = true;
                    scrollY = (event.getY(0) + event.getY(1)) / 2;
                    if (dragging) {
                        mouseButton("left", false);
                        dragging = false;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (multiTouch && event.getPointerCount() >= 2) {
                        float currentY = (event.getY(0) + event.getY(1)) / 2;
                        int steps = (int) ((currentY - scrollY) / dp(28));
                        if (steps != 0) {
                            mouseWheel(steps);
                            scrollY += steps * dp(28);
                            scrolled = true;
                        }
                    } else if (!multiTouch) {
                        if (Math.hypot(event.getX() - startX, event.getY() - startY) > slop) {
                            moved = true;
                            if (!dragging) cancelRemoteLongPress();
                        }
                        queueMouseMove(event.getX(), event.getY());
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    return true;
                case MotionEvent.ACTION_UP:
                    cancelRemoteLongPress();
                    if (dragging) {
                        queueMouseMove(event.getX(), event.getY());
                        mouseButton("left", false);
                    } else if (multiTouch) {
                        if (!scrolled) mouseClick("right", 1);
                    } else if (!moved) {
                        performClick();
                        long now = android.os.SystemClock.uptimeMillis();
                        if (pendingClick != null && now - lastTapAt <= ViewConfiguration.getDoubleTapTimeout()
                                && Math.hypot(event.getX() - lastTapX, event.getY() - lastTapY) < slop * 3) {
                            handler.removeCallbacks(pendingClick);
                            pendingClick = null;
                            lastTapAt = 0;
                            mouseClick("left", 2);
                        } else {
                            if (pendingClick != null) {
                                handler.removeCallbacks(pendingClick);
                                mouseClick("left", 1);
                            }
                            pendingClick = () -> {
                                mouseClick("left", 1);
                                pendingClick = null;
                            };
                            lastTapAt = now;
                            lastTapX = event.getX();
                            lastTapY = event.getY();
                            handler.postDelayed(pendingClick, ViewConfiguration.getDoubleTapTimeout());
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelRemoteLongPress();
                    if (dragging) mouseButton("left", false);
                    return true;
                default:
                    return true;
            }
        }

        private void cancelRemoteLongPress() {
            if (longPress != null) handler.removeCallbacks(longPress);
            longPress = null;
        }

        void resetGesture() {
            cancelRemoteLongPress();
            if (pendingClick != null) handler.removeCallbacks(pendingClick);
            pendingClick = null;
            if (dragging) mouseButton("left", false);
            dragging = false;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private void logout() {
        PikvmClient old = client;
        screen.resetGesture();
        pendingMove.set(null);
        handler.removeCallbacks(textFlush);
        pendingText.setLength(0);
        client = null;
        stopVideo();
        handler.removeCallbacks(powerPoll);
        screen.setImageDrawable(null);
        currentFrame = null;
        modifiers.clear();
        updateModifierButtons();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(remoteInput.getWindowToken(), 0);
        setContentView(loginPage);
        showMessage("Disconnected");
        if (old != null) io.execute(old::logout);
    }

    private void showMessage(String value) {
        String result = value == null ? "Unknown error" : value;
        message.setText(result);
        if (client != null && videoStatus != null) videoStatus.setText(result);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(1); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(0); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout panel() {
        LinearLayout view = column();
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(rounded(PANEL));
        return view;
    }
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        return params;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private EditText field(String hint, boolean password) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setTextSize(15);
        view.setHint(hint);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(rounded(Color.rgb(45, 59, 78)));
        view.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setMinHeight(dp(48));
        return view;
    }
    private Button button(String label, int color, int textColor) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(textColor);
        view.setTextSize(14);
        view.setMinHeight(dp(48));
        view.setBackground(rounded(color));
        return view;
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }
}
