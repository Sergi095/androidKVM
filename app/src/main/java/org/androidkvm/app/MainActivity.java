package org.androidkvm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
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

/** Minimal native Android console for PiKVM-compatible devices. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 24, 40);
    private static final int PANEL = Color.rgb(27, 39, 59);
    private static final int TEXT = Color.rgb(245, 249, 251);
    private static final int MUTED = Color.rgb(170, 188, 200);
    private static final int ACCENT = Color.rgb(76, 205, 184);
    private static final int DANGER = Color.rgb(182, 77, 82);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinkedHashSet<String> modifiers = new LinkedHashSet<>();
    private final List<Button> modifierButtons = new ArrayList<>();
    private PikvmClient client;
    private MjpegReader videoReader;
    private Bitmap currentFrame;
    private LinearLayout loginPanel, consolePanel;
    private EditText addressInput, userInput, passwordInput, pinInput, textInput;
    private ImageView screen;
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
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(16), dp(12) + bars.top, dp(16), dp(24) + bars.bottom);
            return insets;
        });
        scroll.addView(root);
        setContentView(scroll);

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
        loginPanel.addView(text("For a self-signed certificate, enter its verified SHA-256 fingerprint. Passwords are not saved. For 2FA, append the current code to the password.", 12, MUTED), spaced());
        loginButton = button("Connect", ACCENT, BG);
        loginButton.setOnClickListener(view -> login());
        loginPanel.addView(loginButton, spaced());

        consolePanel = column();
        consolePanel.setVisibility(View.GONE);
        root.addView(consolePanel);

        LinearLayout screenPanel = panel();
        consolePanel.addView(screenPanel, spaced());
        videoStatus = text("Connecting to video…", 14, MUTED);
        screenPanel.addView(videoStatus);
        screen = new ImageView(this);
        screen.setBackgroundColor(Color.BLACK);
        screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.setContentDescription("Remote computer screen; tap to click");
        LinearLayout.LayoutParams screenLayout = new LinearLayout.LayoutParams(-1, dp(245));
        screenLayout.topMargin = dp(10);
        screenPanel.addView(screen, screenLayout);
        screen.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                view.performClick();
                tapScreen(event.getX(), event.getY());
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_DOWN;
        });
        screenPanel.addView(text("Tap the screen for a left click.", 12, MUTED), spaced());

        LinearLayout powerPanel = panel();
        consolePanel.addView(powerPanel, spaced());
        powerPanel.addView(text("Power", 20, TEXT));
        powerStatus = text("Checking…", 14, MUTED);
        powerPanel.addView(powerStatus, spaced());
        LinearLayout powerRow = row();
        onButton = button("Turn on", ACCENT, BG);
        offButton = button("Shut down", DANGER, Color.WHITE);
        LinearLayout.LayoutParams equal = new LinearLayout.LayoutParams(0, dp(48), 1);
        powerRow.addView(onButton, equal);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(48), 1);
        right.leftMargin = dp(8);
        powerRow.addView(offButton, right);
        powerPanel.addView(powerRow, spaced());
        onButton.setOnClickListener(view -> setPower(true));
        offButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Shut down remote computer?")
                .setMessage("This presses its power button briefly.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Shut down", (dialog, which) -> setPower(false))
                .show());

        LinearLayout keyboardPanel = panel();
        consolePanel.addView(keyboardPanel, spaced());
        keyboardPanel.addView(text("Keyboard", 20, TEXT));
        keyboardPanel.addView(text("Swipe the key rows sideways for more keys.", 12, MUTED));
        keyboardPanel.addView(keyRow(new String[][]{
                {"Esc", "Escape"}, {"Tab", "Tab"}, {"Ctrl", "ControlLeft"},
                {"Alt", "AltLeft"}, {"Shift", "ShiftLeft"}, {"←", "ArrowLeft"},
                {"↑", "ArrowUp"}, {"↓", "ArrowDown"}, {"→", "ArrowRight"},
                {"Del", "Delete"}, {"Bksp", "Backspace"}, {"Enter", "Enter"}
        }), spaced());
        String[][] functionKeys = new String[12][2];
        for (int n = 1; n <= 12; n++) {
            functionKeys[n - 1][0] = "F" + n;
            functionKeys[n - 1][1] = "F" + n;
        }
        keyboardPanel.addView(keyRow(functionKeys), spaced());
        textInput = field("Type text to send", false);
        textInput.setSingleLine(true);
        textInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        textInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) { sendText(); return true; }
            return false;
        });
        textInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(400)});
        LinearLayout.LayoutParams textInputLayout = new LinearLayout.LayoutParams(-1, dp(48));
        textInputLayout.topMargin = dp(12);
        keyboardPanel.addView(textInput, textInputLayout);
        Button send = button("Send text", ACCENT, BG);
        send.setOnClickListener(view -> sendText());
        keyboardPanel.addView(send);
        keyboardPanel.addView(text("Text uses the KVM's keyboard layout. Special and F keys work separately.", 12, MUTED), spaced());

        Button logout = button("Disconnect and sign out", PANEL, TEXT);
        logout.setOnClickListener(view -> logout());
        consolePanel.addView(logout, spaced());

        message = text("", 13, MUTED);
        message.setMinHeight(dp(30));
        root.addView(message, spaced());
    }

    private HorizontalScrollView keyRow(String[][] keys) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = row();
        for (String[] item : keys) {
            String label = item[0], code = item[1];
            Button key = button(label, Color.rgb(51, 67, 88), TEXT);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(label.length() > 4 ? 68 : 56), dp(48));
            params.rightMargin = dp(6);
            row.addView(key, params);
            if (code.equals("ControlLeft") || code.equals("AltLeft") || code.equals("ShiftLeft")) {
                modifierButtons.add(key);
                key.setTag(new String[]{label, code});
                key.setOnClickListener(view -> toggleModifier(code));
            } else {
                key.setOnClickListener(view -> sendKey(code));
            }
        }
        scroll.addView(row);
        return scroll;
    }

    private void login() {
        String address = addressInput.getText().toString().trim();
        String user = userInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String pin = pinInput.getText().toString().trim();
        if (address.isEmpty() || user.isEmpty() || password.isEmpty()) {
            showMessage("Enter the bridge address, username, and password.");
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
                    loginPanel.setVisibility(View.GONE);
                    consolePanel.setVisibility(View.VISIBLE);
                    loginButton.setEnabled(true);
                    showMessage("Connected");
                    startVideo(next);
                    handler.removeCallbacks(powerPoll);
                    handler.post(powerPoll);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    loginButton.setEnabled(true);
                    showMessage(error.getMessage());
                });
            }
        });
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
                    powerStatus.setText(state.enabled ? (state.on ? "Computer is on" : "Computer is off") : "ATX control unavailable");
                    onButton.setEnabled(state.enabled && !state.on);
                    offButton.setEnabled(state.enabled && state.on);
                    onButton.setAlpha(onButton.isEnabled() ? 1f : 0.45f);
                    offButton.setAlpha(offButton.isEnabled() ? 1f : 0.45f);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (client == active) powerStatus.setText(error.getMessage()); });
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
            button.setText(active ? tag[0] + " ✓" : tag[0]);
            button.setBackground(rounded(active ? ACCENT : Color.rgb(51, 67, 88)));
            button.setTextColor(active ? BG : TEXT);
        }
    }

    private void sendKey(String code) {
        PikvmClient active = client;
        if (active == null) return;
        List<String> held = new ArrayList<>(modifiers);
        modifiers.clear();
        updateModifierButtons();
        io.execute(() -> {
            try { active.sendKey(code, held); }
            catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
        });
    }

    private void sendText() {
        PikvmClient active = client;
        String text = textInput.getText().toString();
        if (active == null || text.isEmpty()) return;
        io.execute(() -> {
            try {
                active.sendText(text);
                runOnUiThread(() -> { if (client == active) { textInput.setText(""); showMessage("Text sent"); } });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(error.getMessage()));
            }
        });
    }

    private void tapScreen(float x, float y) {
        PikvmClient active = client;
        Bitmap frame = currentFrame;
        if (active == null || frame == null || screen.getWidth() == 0 || screen.getHeight() == 0) return;
        float scale = Math.min((float) screen.getWidth() / frame.getWidth(),
                (float) screen.getHeight() / frame.getHeight());
        float shownWidth = frame.getWidth() * scale, shownHeight = frame.getHeight() * scale;
        float left = (screen.getWidth() - shownWidth) / 2, top = (screen.getHeight() - shownHeight) / 2;
        if (x < left || x > left + shownWidth || y < top || y > top + shownHeight) return;
        float nx = (x - left) / shownWidth, ny = (y - top) / shownHeight;
        io.execute(() -> {
            try { active.tap(nx, ny); }
            catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
        });
    }

    private void logout() {
        PikvmClient old = client;
        client = null;
        stopVideo();
        handler.removeCallbacks(powerPoll);
        screen.setImageDrawable(null);
        currentFrame = null;
        modifiers.clear();
        updateModifierButtons();
        consolePanel.setVisibility(View.GONE);
        loginPanel.setVisibility(View.VISIBLE);
        showMessage("Disconnected");
        if (old != null) io.execute(old::logout);
    }

    private void showMessage(String value) { message.setText(value == null ? "Unknown error" : value); }
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
