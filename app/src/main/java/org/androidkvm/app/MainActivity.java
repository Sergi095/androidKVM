package org.androidkvm.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.TextureView;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Minimal native Android console for PiKVM and NanoKVM devices. */
public final class MainActivity extends FragmentActivity {
    private static final int BG = Color.rgb(8, 16, 31);
    private static final int CONSOLE_BG = Color.BLACK;
    private static final int PANEL = Color.rgb(27, 39, 59);
    private static final int SURFACE = Color.rgb(39, 54, 74);
    private static final int TEXT = Color.rgb(245, 249, 251);
    private static final int MUTED = Color.rgb(160, 178, 195);
    private static final int ACCENT = Color.rgb(41, 197, 239);
    private static final int KEY_ACCENT = Color.rgb(76, 205, 184);
    private static final int DANGER = Color.rgb(182, 77, 82);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService mouseIo = Executors.newSingleThreadExecutor();
    private final ExecutorService updatesIo = Executors.newSingleThreadExecutor();
    private final ExecutorService wolIo = Executors.newSingleThreadExecutor();
    private final ExecutorService securityIo = Executors.newSingleThreadExecutor();
    private final StringBuilder pendingText = new StringBuilder();
    private final Runnable textFlush = this::flushTextNow;
    private final AtomicReference<PointF> pendingMove = new AtomicReference<>();
    private final AtomicBoolean moveQueued = new AtomicBoolean();
    private final LinkedHashSet<String> modifiers = new LinkedHashSet<>();
    private final List<Button> modifierButtons = new ArrayList<>();
    private final List<AlertDialog> openDialogs = new ArrayList<>();
    private AlertDialog securitySettingsDialog;
    private AlertDialog videoSettingsDialog;
    private NanoTerminalPanel terminalPanel;
    private PikvmClient client;
    private MjpegReader videoReader;
    private H264Reader h264Reader;
    private Bitmap currentFrame;
    private ScrollView loginPage;
    private LinearLayout consolePage;
    private LinearLayout loginPanel;
    private LinearLayout savedPanel;
    private LinearLayout kvmHome, updatesHome;
    private LinearLayout extraKeys;
    private EditText addressInput, userInput, passwordInput;
    private Button deviceTypeInput;
    private String selectedDeviceType = "";
    private String trustedAddress = "", trustedFingerprint = "";
    private String activeAddress = "", activeUser = "", activeType = "pikvm";
    private CheckBox rememberInput;
    private ProfileStore profileStore;
    private SecurityStore securityStore;
    private BiometricPrompt biometricPrompt;
    private ScrollView lockPage;
    private View resumePage;
    private EditText lockPinInput;
    private TextView lockStatus;
    private boolean locked, foreground, promptBusy, pinCheckBusy, pendingBiometricUnlock;
    private boolean enablingBiometrics;
    private RemoteScreen screen;
    private FrameLayout videoArea;
    private TextureView fastVideo;
    private int videoWidth, videoHeight;
    private RemoteInput remoteInput;
    private TextView message, videoStatus, powerStatus, consoleTitle, sectionTitle;
    private TextView updateStatus;
    private Button loginButton, onButton, offButton, addKvmButton, closeFormButton;
    private Button kvmTab, updatesTab, checkUpdateButton, installUpdateButton;
    private UpdateManager updateManager;
    private UpdateManager.Release latestRelease;
    private File pendingInstall;
    private boolean updateBusy;
    private static final int INSTALL_PERMISSION_REQUEST = 1701;
    private boolean trackpadMode;
    private boolean fillScreen;
    private int screenScalePercent = 100;
    private float videoOffsetX, videoOffsetY;
    private float pointerX = 0.5f, pointerY = 0.5f;
    private int consoleTopInset, consoleBottomInset;

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
        profileStore = new ProfileStore(this);
        fillScreen = getSharedPreferences("video_preferences", MODE_PRIVATE).getBoolean("fill_screen", false);
        securityStore = new SecurityStore(this);
        updateManager = new UpdateManager(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        promptBusy = false;
                        if (!foreground) {
                            if (!enablingBiometrics) pendingBiometricUnlock = true;
                            return;
                        }
                        if (enablingBiometrics) {
                            enablingBiometrics = false;
                            try {
                                securityStore.setBiometrics(true);
                                refreshSecureFlag();
                                showSecuritySettings();
                            } catch (Exception error) {
                                Toast.makeText(MainActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        } else if (locked) unlockApp();
                    }

                    @Override public void onAuthenticationError(int code, CharSequence message) {
                        promptBusy = false;
                        if (enablingBiometrics) enablingBiometrics = false;
                        if (locked && lockStatus != null && foreground) {
                            lockStatus.setText("Use the unlock button to try again.");
                        }
                    }

                    @Override public void onAuthenticationFailed() {
                        if (locked && lockStatus != null) lockStatus.setText("Not recognized. Try again.");
                    }
                });
        if (state != null) enablingBiometrics = state.getBoolean("enabling_biometrics", false);
        refreshSecureFlag();
        if (securityStore.enabled()) displayLockPage();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("enabling_biometrics", enablingBiometrics);
        super.onSaveInstanceState(state);
    }

    @Override public void onStart() {
        super.onStart();
        foreground = true;
        if (locked) {
            if (pendingBiometricUnlock) {
                pendingBiometricUnlock = false;
                unlockApp();
            }
        } else if (client != null) {
            startVideo(client);
            handler.removeCallbacks(powerPoll);
            handler.post(powerPoll);
        }
    }

    @Override public void onResume() {
        super.onResume();
        foreground = true;
        if (locked && securityStore.hasBiometrics()) {
            handler.postDelayed(this::promptBiometricUnlock, 150);
        }
    }

    @Override public void onPause() {
        foreground = false;
        if (securityStore.enabled() && !locked && !promptBusy) {
            for (AlertDialog dialog : new ArrayList<>(openDialogs)) dialog.dismiss();
            displayLockPage();
        }
        super.onPause();
    }

    @Override public void onStop() {
        foreground = false;
        for (AlertDialog dialog : new ArrayList<>(openDialogs)) dialog.dismiss();
        closeNanoTerminal(false);
        handler.removeCallbacks(powerPoll);
        stopVideo();
        if (client != null) client.stopEvents();
        if (securityStore.enabled() && !locked) displayLockPage();
        super.onStop();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(powerPoll);
        closeNanoTerminal(false);
        stopVideo();
        io.shutdownNow();
        mouseIo.shutdownNow();
        updatesIo.shutdownNow();
        wolIo.shutdownNow();
        securityIo.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (hideVisibleKeyboard()) return;
        for (int index = openDialogs.size() - 1; index >= 0; index--) {
            AlertDialog dialog = openDialogs.get(index);
            if (dialog.isShowing()) { dialog.dismiss(); return; }
        }
        if (terminalPanel != null) { closeNanoTerminal(true); return; }
        if (locked) { moveTaskToBack(true); return; }
        if (client != null) { logout(); return; }
        if (updatesHome.getVisibility() == View.VISIBLE) { showHomeTab(false); return; }
        if (loginPanel.getVisibility() == View.VISIBLE && !profileStore.list().isEmpty()) {
            closeAddKvmForm();
            return;
        }
        if (securityStore.enabled()) displayLockPage();
        moveTaskToBack(true);
    }

    private boolean hideVisibleKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return false;
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        boolean visible = Build.VERSION.SDK_INT >= 30 && insets != null
                && insets.isVisible(WindowInsets.Type.ime());
        if (!visible) {
            Rect frame = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
            Point display = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(display);
            visible = display.y - frame.bottom > dp(160);
        }
        if (!visible) return false;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
        return true;
    }

    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (client != null) {
            menu.getMenu().add("Video settings").setOnMenuItemClickListener(item -> {
                showVideoSettings();
                return true;
            });
            menu.getMenu().add("Reset HID").setOnMenuItemClickListener(item -> {
                confirmHidReset();
                return true;
            });
            if (client instanceof NanoKvmClient) {
                menu.getMenu().add("NanoKVM settings").setOnMenuItemClickListener(item -> {
                    showNanoSettings();
                    return true;
                });
                menu.getMenu().add("NanoKVM terminal").setOnMenuItemClickListener(item -> {
                    openNanoTerminal(null);
                    return true;
                });
                menu.getMenu().add("Serial terminal").setOnMenuItemClickListener(item -> {
                    showSerialTerminalSetup();
                    return true;
                });
            }
        }
        menu.getMenu().add("Security settings").setOnMenuItemClickListener(item -> {
            showSecuritySettings();
            return true;
        });
        if (securityStore.enabled()) {
            menu.getMenu().add("Lock now").setOnMenuItemClickListener(item -> {
                displayLockPage();
                if (securityStore.hasBiometrics()) handler.post(this::promptBiometricUnlock);
                return true;
            });
        }
        menu.show();
    }

    private void confirmHidReset() {
        PikvmClient active = client;
        if (active == null) return;
        showTrackedDialog(new AlertDialog.Builder(this)
                .setTitle("Reset KVM input?")
                .setMessage("Keyboard and mouse may disconnect briefly.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset HID", (dialog, which) -> io.execute(() -> {
                    try {
                        active.resetHid();
                        active.stopEvents();
                        active.ensureEvents();
                        runOnUiThread(() -> {
                            if (client == active) Toast.makeText(this, "HID reset", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            if (client == active) showMessage(error.getMessage());
                        });
                    }
                })).create());
    }

    private void openNanoTerminal(String serialCommand) {
        if (!(client instanceof NanoKvmClient) || locked) return;
        if (terminalPanel != null) closeNanoTerminal(false);
        NanoKvmClient active = (NanoKvmClient) client;
        handler.removeCallbacks(powerPoll);
        stopVideo();
        active.stopEvents();
        terminalPanel = new NanoTerminalPanel(this, active, serialCommand, this::consumeModifiers);
        videoArea.addView(terminalPanel, new FrameLayout.LayoutParams(-1, -1));
        screen.setVisibility(View.GONE);
        fastVideo.setVisibility(View.GONE);
        videoStatus.setVisibility(View.GONE);
        consoleTitle.setText(serialCommand == null ? "NanoKVM terminal" : "Serial terminal");
    }

    private void closeNanoTerminal(boolean resumeVideo) {
        if (terminalPanel == null) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focused = getCurrentFocus();
        if (keyboard != null && focused != null)
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        videoArea.removeView(terminalPanel);
        terminalPanel.close();
        terminalPanel = null;
        modifiers.clear();
        updateModifierButtons();
        screen.setVisibility(View.VISIBLE);
        videoStatus.setVisibility(View.VISIBLE);
        consoleTitle.setText(activeAddress);
        if (resumeVideo && !locked && client != null && foreground) {
            startVideo(client);
            handler.removeCallbacks(powerPoll);
            handler.post(powerPoll);
        }
    }

    private void showNanoSettings() {
        if (!(client instanceof NanoKvmClient) || locked) return;
        NanoKvmClient active = (NanoKvmClient) client;
        LinearLayout items = column();
        items.setPadding(dp(18), 0, dp(18), dp(12));
        items.addView(text("ACCOUNT", 12, ACCENT), spaced());
        nanoSettingButton(items, "Change NanoKVM password", () -> showNanoPassword(active));
        items.addView(text("DEVICE", 12, ACCENT), spaced());
        nanoSettingButton(items, "Device information", () -> showNanoInfo(active));
        nanoSettingButton(items, "Hostname", () -> nanoTextSetting(active, "Hostname",
                "/api/vm/hostname", "hostname", "[A-Za-z0-9][A-Za-z0-9-]{0,62}",
                "Use letters, numbers, and hyphens."));
        nanoSettingButton(items, "Web page title", () -> nanoTextSetting(active, "Web page title",
                "/api/vm/web-title", "title", ".{1,60}", "Enter 1–60 characters."));
        nanoSettingButton(items, "SSH", () -> nanoToggle(active, "SSH", "/api/vm/ssh", "enabled",
                "/api/vm/ssh/enable", "/api/vm/ssh/disable"));
        nanoSettingButton(items, "mDNS", () -> nanoToggle(active, "mDNS", "/api/vm/mdns", "enabled",
                "/api/vm/mdns/enable", "/api/vm/mdns/disable"));
        nanoSettingButton(items, "HDMI capture", () -> nanoToggle(active, "HDMI capture", "/api/vm/hdmi",
                "enabled", "/api/vm/hdmi/enable", "/api/vm/hdmi/disable"));
        nanoSettingButton(items, "HDMI idle timeout", () -> nanoNumberSetting(active,
                "HDMI idle timeout (minutes)", "/api/vm/hdmi", "idleTimeout",
                "/api/vm/hdmi/timeout", "minutes", 0, 10080));
        nanoSettingButton(items, "Reset HDMI capture", () -> confirmNanoAction(active,
                "Reset HDMI capture?", "/api/vm/hdmi/reset"));
        nanoSettingButton(items, "OLED sleep", () -> showNanoOled(active));
        nanoSettingButton(items, "Mouse jiggler", () -> showNanoMouseJiggler(active));
        nanoSettingButton(items, "Virtual USB devices", () -> showNanoVirtualDevices(active));
        nanoSettingButton(items, "Swap file", () -> showNanoSwap(active));
        nanoSettingButton(items, "Memory limit", () -> showNanoMemoryLimit(active));
        items.addView(text("NETWORK", 12, ACCENT), spaced());
        nanoSettingButton(items, "Wi-Fi", () -> showNanoWifi(active));
        nanoSettingButton(items, "DNS", () -> showNanoDns(active));
        nanoSettingButton(items, "HTTPS", () -> showNanoHttps(active));
        items.addView(text("SYSTEM", 12, ACCENT), spaced());
        nanoSettingButton(items, "Reboot NanoKVM", () -> confirmNanoAction(active,
                "Reboot NanoKVM?", "/api/vm/system/reboot"));
        nanoSettingButton(items, "Full NanoKVM web interface", () -> showNanoWebInterface(active));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(items);
        showTrackedDialog(new AlertDialog.Builder(this).setTitle("NanoKVM settings")
                .setView(scroll).setPositiveButton("Done", null).create());
    }

    private void nanoSettingButton(LinearLayout items, String label, Runnable action) {
        Button button = button(label + "  ›", SURFACE, TEXT);
        button.setOnClickListener(view -> action.run());
        items.addView(button, spaced());
    }

    private void loadNanoSetting(NanoKvmClient active, String path,
                                 java.util.function.Consumer<JSONObject> loaded) {
        io.execute(() -> {
            try {
                JSONObject result = active.setting(path);
                runOnUiThread(() -> {
                    if (client == active && !locked) loaded.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (client == active) showMessage(error.getMessage());
                });
            }
        });
    }

    private JSONObject nanoValue(String key, Object value) {
        JSONObject data = new JSONObject();
        try { data.put(key, value); }
        catch (Exception ignored) { }
        return data;
    }

    private void applyNanoSetting(NanoKvmClient active, String path, JSONObject data, Runnable after) {
        io.execute(() -> {
            try {
                active.updateSetting(path, data);
                runOnUiThread(() -> {
                    if (client != active || locked) return;
                    Toast.makeText(this, "NanoKVM setting saved", Toast.LENGTH_SHORT).show();
                    if (after != null) after.run();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (client == active) showMessage(error.getMessage());
                });
            }
        });
    }

    private void showNanoPassword(NanoKvmClient active) {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(8));
        EditText current = field("Current password", true);
        EditText next = field("New password", true);
        EditText confirm = field("Repeat new password", true);
        form.addView(current);
        form.addView(next, spaced());
        form.addView(confirm, spaced());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Change NanoKVM password")
                .setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Change", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String oldPassword = current.getText().toString();
                    String newPassword = next.getText().toString();
                    if (oldPassword.isEmpty()) { current.setError("Enter the current password."); return; }
                    if (newPassword.length() < 8) { next.setError("Use at least 8 characters."); return; }
                    if (!newPassword.equals(confirm.getText().toString())) {
                        confirm.setError("Passwords do not match."); return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    io.execute(() -> {
                        try {
                            active.changePassword(oldPassword, newPassword);
                            String savedPin = null;
                            for (ProfileStore.Profile saved : profileStore.list()) {
                                if (saved.type.equals("nanokvm") && saved.address.equals(activeAddress)
                                        && saved.user.equals(activeUser)) { savedPin = saved.pin; break; }
                            }
                            boolean savedLoginUpdated = true;
                            if (savedPin != null) {
                                try { profileStore.save("nanokvm", activeAddress, activeUser,
                                        savedPin, newPassword); }
                                catch (Exception saveError) { savedLoginUpdated = false; }
                            }
                            active.stopEvents();
                            boolean reconnected = true;
                            try { active.login(activeUser, newPassword); }
                            catch (Exception loginError) { reconnected = false; }
                            boolean finalSaved = savedLoginUpdated;
                            boolean finalReconnected = reconnected;
                            runOnUiThread(() -> {
                                if (client != active) return;
                                dialog.dismiss();
                                renderSavedProfiles();
                                if (finalReconnected) {
                                    if (terminalPanel != null) closeNanoTerminal(true);
                                    else startVideo(active);
                                } else logout();
                                String result = "NanoKVM password changed"
                                        + (finalReconnected ? "" : ". Reconnect with the new password")
                                        + (finalSaved ? "" : ". Saved login was not updated");
                                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (client != active) return;
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                showMessage("Password change: " + error.getMessage());
                            });
                        }
                    });
                }));
        showTrackedDialog(dialog);
    }

    private void nanoTextSetting(NanoKvmClient active, String title, String path,
                                 String key, String pattern, String validation) {
        loadNanoSetting(active, path, state -> {
            EditText input = field(title, false);
            input.setText(state.optString(key, ""));
            LinearLayout form = column();
            form.setPadding(dp(20), 0, dp(20), dp(8));
            form.addView(input);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(form)
                    .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        String value = input.getText().toString().trim();
                        if (!value.matches(pattern) || ("hostname".equals(key) && value.endsWith("-"))) {
                            input.setError(validation); return;
                        }
                        applyNanoSetting(active, path, nanoValue(key, value), dialog::dismiss);
                    }));
            showTrackedDialog(dialog);
        });
    }

    private void nanoToggle(NanoKvmClient active, String title, String readPath,
                            String key, String onPath, String offPath) {
        loadNanoSetting(active, readPath, state -> {
            boolean enabled = state.optBoolean(key);
            showTrackedDialog(new AlertDialog.Builder(this).setTitle(title)
                    .setSingleChoiceItems(new String[]{"Off", "On"}, enabled ? 1 : 0,
                            (dialog, choice) -> {
                                if ((choice == 1) != enabled)
                                    applyNanoSetting(active, choice == 1 ? onPath : offPath,
                                            new JSONObject(), dialog::dismiss);
                                else dialog.dismiss();
                            }).setNegativeButton("Cancel", null).create());
        });
    }

    private void nanoNumberSetting(NanoKvmClient active, String title, String readPath,
                                   String readKey, String writePath, String writeKey, int min, int max) {
        loadNanoSetting(active, readPath, state -> {
            EditText input = field(title, false);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setText(Integer.toString(state.optInt(readKey, min)));
            LinearLayout form = column();
            form.setPadding(dp(20), 0, dp(20), dp(8));
            form.addView(input);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(form)
                    .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        int value;
                        try { value = Integer.parseInt(input.getText().toString()); }
                        catch (NumberFormatException error) { input.setError("Enter a number."); return; }
                        if (value < min || value > max) {
                            input.setError("Choose " + min + "–" + max + "."); return;
                        }
                        applyNanoSetting(active, writePath, nanoValue(writeKey, value), dialog::dismiss);
                    }));
            showTrackedDialog(dialog);
        });
    }

    private void confirmNanoAction(NanoKvmClient active, String title, String path) {
        showTrackedDialog(new AlertDialog.Builder(this).setTitle(title)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) ->
                        applyNanoSetting(active, path, new JSONObject(), null)).create());
    }

    private void showNanoInfo(NanoKvmClient active) {
        loadNanoSetting(active, "/api/vm/info", info -> {
            StringBuilder details = new StringBuilder();
            details.append("Application: ").append(info.optString("application", "Unknown"))
                    .append("\nSystem image: ").append(info.optString("image", "Unknown"));
            String mdns = info.optString("mdns", "");
            if (!mdns.isEmpty()) details.append("\nmDNS: ").append(mdns);
            JSONArray ips = info.optJSONArray("ips");
            if (ips != null) for (int index = 0; index < ips.length(); index++) {
                JSONObject item = ips.optJSONObject(index);
                if (item != null) details.append("\n")
                        .append(item.optString("name", "Interface"))
                        .append(": ").append(item.optString("addr", ""));
            }
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("NanoKVM information")
                    .setMessage(details.toString()).setPositiveButton("Done", null).create());
        });
    }

    private void showNanoOled(NanoKvmClient active) {
        loadNanoSetting(active, "/api/vm/oled", state -> {
            if (!state.optBoolean("exist")) {
                showMessage("This NanoKVM has no OLED screen."); return;
            }
            int[] seconds = {0, 15, 30, 60, 180, 300, 600, 1800, 3600};
            String[] choices = new String[seconds.length];
            int selected = -1;
            for (int index = 0; index < seconds.length; index++) {
                choices[index] = seconds[index] == 0 ? "Never sleep" : seconds[index] + " seconds";
                if (seconds[index] == state.optInt("sleep")) selected = index;
            }
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("OLED sleep")
                    .setSingleChoiceItems(choices, selected, (dialog, index) ->
                            applyNanoSetting(active, "/api/vm/oled",
                                    nanoValue("sleep", seconds[index]), dialog::dismiss))
                    .setNegativeButton("Cancel", null).create());
        });
    }

    private void showNanoMouseJiggler(NanoKvmClient active) {
        loadNanoSetting(active, "/api/vm/mouse-jiggler", state -> {
            int selected = !state.optBoolean("enabled") ? 0
                    : "absolute".equals(state.optString("mode")) ? 2 : 1;
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("Mouse jiggler")
                    .setSingleChoiceItems(new String[]{"Off", "Relative", "Absolute"}, selected,
                            (dialog, index) -> {
                                JSONObject data = nanoValue("enabled", index != 0);
                                try { data.put("mode", index == 2 ? "absolute" : "relative"); }
                                catch (Exception ignored) { }
                                applyNanoSetting(active, "/api/vm/mouse-jiggler/", data, dialog::dismiss);
                            }).setNegativeButton("Cancel", null).create());
        });
    }

    private void showNanoVirtualDevices(NanoKvmClient active) {
        loadNanoSetting(active, "/api/vm/device/virtual", state -> {
            String[] devices = {"network", "media", "disk"};
            String[] labels = {"USB network", "Virtual media", "USB disk"};
            String[] options = new String[devices.length];
            for (int index = 0; index < devices.length; index++)
                options[index] = labels[index] + " · " + (state.optBoolean(devices[index]) ? "On" : "Off");
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("Virtual USB devices")
                    .setItems(options, (dialog, index) -> {
                        boolean enable = !state.optBoolean(devices[index]);
                        showTrackedDialog(new AlertDialog.Builder(this)
                                .setTitle((enable ? "Enable " : "Disable ") + labels[index] + "?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Continue", (confirm, choice) -> {
                                    JSONObject data = nanoValue("device", devices[index]);
                                    try { data.put("enable", enable); }
                                    catch (Exception ignored) { }
                                    applyNanoSetting(active, "/api/vm/device/virtual", data,
                                            () -> showNanoVirtualDevices(active));
                                }).create());
                    }).setNegativeButton("Done", null).create());
        });
    }

    private void showNanoSwap(NanoKvmClient active) {
        loadNanoSetting(active, "/api/vm/swap", state -> {
            int[] sizes = {0, 64, 128, 256, 512};
            String[] labels = {"Off", "64 MB", "128 MB", "256 MB", "512 MB"};
            int selected = -1;
            for (int index = 0; index < sizes.length; index++)
                if (state.optInt("size") == sizes[index]) selected = index;
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("Swap file")
                    .setSingleChoiceItems(labels, selected, (dialog, index) ->
                            applyNanoSetting(active, "/api/vm/swap", nanoValue("size", sizes[index]),
                                    dialog::dismiss))
                    .setNegativeButton("Cancel", null).create());
        });
    }

    private void showNanoMemoryLimit(NanoKvmClient active) {
        loadNanoSetting(active, "/api/vm/memory/limit", state -> {
            boolean enabled = state.optBoolean("enabled");
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("Memory limit")
                    .setMessage("Limit NanoKVM application memory use to 75 MB.")
                    .setSingleChoiceItems(new String[]{"Off", "On"}, enabled ? 1 : 0,
                            (dialog, index) -> {
                                JSONObject data = nanoValue("enabled", index == 1);
                                try { data.put("limit", index == 1 ? 75 : 0); }
                                catch (Exception ignored) { }
                                applyNanoSetting(active, "/api/vm/memory/limit", data,
                                        dialog::dismiss);
                            }).setNegativeButton("Cancel", null).create());
        });
    }

    private void showNanoWifi(NanoKvmClient active) {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(8));
        TextView status = text("Checking NanoKVM Wi-Fi…", 13, MUTED);
        form.addView(status);
        EditText ssid = field("Wi-Fi name", false);
        EditText password = field("Wi-Fi password", true);
        form.addView(ssid, spaced());
        form.addView(password, spaced());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("NanoKVM Wi-Fi")
                .setView(form).setNeutralButton("Disconnect", null)
                .setNegativeButton("Close", null).setPositiveButton("Connect", null).create();
        dialog.setOnShowListener(ignored -> {
            Button connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button disconnect = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            connect.setEnabled(false);
            disconnect.setVisibility(View.GONE);
            connect.setOnClickListener(view -> {
                String name = ssid.getText().toString().trim();
                String secret = password.getText().toString();
                if (name.isEmpty()) { ssid.setError("Enter the Wi-Fi name."); return; }
                if (secret.isEmpty()) { password.setError("Enter the Wi-Fi password."); return; }
                JSONObject data = nanoValue("ssid", name);
                try { data.put("password", secret); }
                catch (Exception ignored2) { }
                submitNanoWifi(active, "/api/network/wifi/connect", data, dialog,
                        status, ssid, password, connect, disconnect, "Connecting to " + name + "…");
            });
            disconnect.setOnClickListener(view -> submitNanoWifi(active,
                    "/api/network/wifi/disconnect", new JSONObject(), dialog,
                    status, ssid, password, connect, disconnect, "Disconnecting Wi-Fi…"));
            loadNanoWifi(active, dialog, status, ssid, password, connect, disconnect);
        });
        showTrackedDialog(dialog);
    }

    private void loadNanoWifi(NanoKvmClient active, AlertDialog dialog, TextView status,
                              EditText ssid, EditText password, Button connect, Button disconnect) {
        io.execute(() -> {
            try {
                JSONObject state = active.setting("/api/network/wifi");
                runOnUiThread(() -> {
                    if (client != active || locked || !dialog.isShowing()) return;
                    boolean supported = state.optBoolean("supported");
                    boolean apMode = state.optBoolean("apMode");
                    boolean connected = state.optBoolean("connected");
                    if (!supported) status.setText("Wi-Fi is unavailable on this NanoKVM.");
                    else if (apMode) status.setText("NanoKVM is in Wi-Fi setup mode. Use its setup page to join a network.");
                    else status.setText(connected ? "Connected to " + state.optString("ssid", "Wi-Fi")
                            : "Not connected");
                    ssid.setVisibility(supported && !apMode ? View.VISIBLE : View.GONE);
                    password.setVisibility(supported && !apMode ? View.VISIBLE : View.GONE);
                    connect.setVisibility(supported && !apMode ? View.VISIBLE : View.GONE);
                    connect.setEnabled(supported && !apMode);
                    disconnect.setVisibility(supported && !apMode && connected ? View.VISIBLE : View.GONE);
                    disconnect.setEnabled(true);
                    if (connected) ssid.setText(state.optString("ssid", ""));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (client != active || !dialog.isShowing()) return;
                    status.setText("Could not read NanoKVM Wi-Fi: " + error.getMessage());
                    connect.setEnabled(false);
                    disconnect.setVisibility(View.GONE);
                });
            }
        });
    }

    private void submitNanoWifi(NanoKvmClient active, String path, JSONObject data,
                                AlertDialog dialog, TextView status, EditText ssid,
                                EditText password, Button connect, Button disconnect, String progress) {
        connect.setEnabled(false);
        disconnect.setEnabled(false);
        status.setText(progress + " This may take a while.");
        io.execute(() -> {
            try {
                active.updateSetting(path, data);
                runOnUiThread(() -> {
                    if (client != active || locked || !dialog.isShowing()) return;
                    password.setText("");
                    status.setText("Wi-Fi updated. Checking the connection…");
                    loadNanoWifi(active, dialog, status, ssid, password, connect, disconnect);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (client != active || !dialog.isShowing()) return;
                    status.setText("Wi-Fi change failed: " + error.getMessage());
                    connect.setEnabled(true);
                    disconnect.setEnabled(true);
                });
            }
        });
    }

    private void showNanoDns(NanoKvmClient active) {
        loadNanoSetting(active, "/api/network/dns", state -> {
            LinearLayout form = column();
            form.setPadding(dp(20), 0, dp(20), dp(8));
            form.addView(text("Use DHCP for automatic DNS, or enter up to six IP addresses, one per line.",
                    13, MUTED));
            CheckBox dhcp = new CheckBox(this);
            dhcp.setText("Automatic (DHCP)");
            dhcp.setTextColor(TEXT);
            dhcp.setChecked(!"manual".equals(state.optString("mode")));
            form.addView(dhcp, spaced());
            EditText servers = field("DNS servers", false);
            servers.setSingleLine(false);
            servers.setMinLines(3);
            JSONArray current = state.optJSONArray("servers");
            StringBuilder values = new StringBuilder();
            if (current != null) for (int index = 0; index < current.length(); index++) {
                if (values.length() > 0) values.append('\n');
                values.append(current.optString(index));
            }
            servers.setText(values.toString());
            servers.setEnabled(!dhcp.isChecked());
            dhcp.setOnCheckedChangeListener((button, checked) -> servers.setEnabled(!checked));
            form.addView(servers, spaced());
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("NanoKVM DNS")
                    .setView(form).setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", null).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        JSONArray addresses = new JSONArray();
                        if (!dhcp.isChecked()) {
                            String[] lines = servers.getText().toString().trim().split("[\\s,;]+");
                            for (String line : lines) if (!line.isEmpty()) {
                                if (!line.matches("[0-9A-Fa-f:.]+")) {
                                    servers.setError("Enter IP addresses only."); return;
                                }
                                addresses.put(line);
                            }
                            if (addresses.length() < 1 || addresses.length() > 6) {
                                servers.setError("Enter 1–6 DNS servers."); return;
                            }
                        }
                        JSONObject data = nanoValue("mode", dhcp.isChecked() ? "dhcp" : "manual");
                        try { data.put("servers", addresses); }
                        catch (Exception ignored2) { }
                        applyNanoSetting(active, "/api/network/dns", data, dialog::dismiss);
                    }));
            showTrackedDialog(dialog);
        });
    }

    private void showNanoHttps(NanoKvmClient active) {
        boolean https = active.baseUrl.startsWith("https://");
        showTrackedDialog(new AlertDialog.Builder(this)
                .setTitle(https ? "Disable NanoKVM HTTPS?" : "Enable NanoKVM HTTPS?")
                .setMessage("The NanoKVM web server will restart. Reconnect using "
                        + (https ? "http://" : "https://") + " at the same host and port."
                        + (https ? "" : " You may need to trust its new certificate."))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Change", (dialog, which) ->
                        applyNanoSetting(active, "/api/vm/tls", nanoValue("enabled", !https),
                                () -> {
                                    Toast.makeText(this, "Reconnect with the new URL scheme",
                                            Toast.LENGTH_LONG).show();
                                    logout();
                                })).create());
    }

    private void showNanoWebInterface(NanoKvmClient active) {
        if (active.cookie == null) {
            showMessage("NanoKVM session is unavailable. Reconnect first.");
            return;
        }
        LinearLayout layout = column();
        layout.setBackgroundColor(BG);
        layout.setPadding(dp(12), 0, dp(12), 0);
        layout.addView(text("Use the gear icon for NanoKVM's remaining settings. Change your password in androidKVM above to keep a saved login updated.",
                12, MUTED), spaced());
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !sameNanoOrigin(active, request.getUrl().toString());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (sameNanoOrigin(active, request.getUrl().toString())) return null;
                return new WebResourceResponse("text/plain", "UTF-8",
                        new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (!sameNanoOrigin(active, error.getUrl()) || !matchesNanoCertificate(active, error)) {
                    handler.cancel();
                    showMessage("NanoKVM certificate changed. Reconnect and trust the new certificate first.");
                    return;
                }
                handler.proceed();
            }
        });
        layout.addView(web, new LinearLayout.LayoutParams(-1,
                getResources().getDisplayMetrics().heightPixels - dp(310)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("NanoKVM web interface")
                .setView(layout).setPositiveButton("Done", null).create();
        showTrackedDialog(dialog, () -> {
            web.stopLoading();
            layout.removeView(web);
            web.destroy();
        });
        cookies.setCookie(active.baseUrl, active.cookie + "; Path=/; HttpOnly; SameSite=Lax"
                + (active.baseUrl.startsWith("https://") ? "; Secure" : ""), loaded -> {
            if (dialog.isShowing() && client == active) web.loadUrl(active.baseUrl);
        });
    }

    private static boolean sameNanoOrigin(NanoKvmClient active, String target) {
        try {
            URI source = new URI(active.baseUrl);
            URI candidate = new URI(target);
            int sourcePort = source.getPort() < 0 ? ("https".equalsIgnoreCase(source.getScheme()) ? 443 : 80)
                    : source.getPort();
            int candidatePort = candidate.getPort() < 0
                    ? ("https".equalsIgnoreCase(candidate.getScheme()) ? 443 : 80) : candidate.getPort();
            return source.getScheme().equalsIgnoreCase(candidate.getScheme())
                    && source.getHost().equalsIgnoreCase(candidate.getHost())
                    && sourcePort == candidatePort;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean matchesNanoCertificate(NanoKvmClient active, SslError error) {
        if (!active.pinned || error.getCertificate() == null) return false;
        try {
            X509Certificate certificate = Build.VERSION.SDK_INT >= 29
                    ? error.getCertificate().getX509Certificate() : null;
            if (certificate == null) {
                Bundle saved = SslCertificate.saveState(error.getCertificate());
                byte[] encoded = saved == null ? null : saved.getByteArray("x509-certificate");
                if (encoded == null) return false;
                certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(encoded));
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
            return active.certificatePin.equals(hex.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showSerialTerminalSetup() {
        if (!(client instanceof NanoKvmClient)) return;
        String prefix = client.baseUrl + "|";
        android.content.SharedPreferences saved = getSharedPreferences("serial_preferences", MODE_PRIVATE);
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(12));
        form.addView(text("The port is on the NanoKVM. Common ports are /dev/ttyS1 and /dev/ttyS2.",
                13, MUTED));
        form.addView(text("Port", 13, MUTED), spaced());
        EditText port = field("/dev/ttyS1", false);
        port.setText(saved.getString(prefix + "port", "/dev/ttyS1"));
        form.addView(port);
        Spinner baud = serialChoice(form, "Baud rate",
                new String[]{"9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600"},
                saved.getString(prefix + "baud", "115200"));
        Spinner parity = serialChoice(form, "Parity", new String[]{"none", "even", "odd"},
                saved.getString(prefix + "parity", "none"));
        Spinner flow = serialChoice(form, "Flow control", new String[]{"none", "soft", "hard"},
                saved.getString(prefix + "flow", "none"));
        Spinner bits = serialChoice(form, "Data bits", new String[]{"5", "6", "7", "8"},
                saved.getString(prefix + "bits", "8"));
        Spinner stops = serialChoice(form, "Stop bits", new String[]{"1", "2"},
                saved.getString(prefix + "stops", "1"));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Serial terminal")
                .setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Open", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String path = port.getText().toString().trim();
                    if (!path.matches("/dev/[A-Za-z0-9._-]+") || path.contains("..")) {
                        port.setError("Enter a port such as /dev/ttyS1.");
                        return;
                    }
                    String baudValue = baud.getSelectedItem().toString();
                    String parityValue = parity.getSelectedItem().toString();
                    String flowValue = flow.getSelectedItem().toString();
                    String bitsValue = bits.getSelectedItem().toString();
                    String stopsValue = stops.getSelectedItem().toString();
                    saved.edit().putString(prefix + "port", path).putString(prefix + "baud", baudValue)
                            .putString(prefix + "parity", parityValue).putString(prefix + "flow", flowValue)
                            .putString(prefix + "bits", bitsValue).putString(prefix + "stops", stopsValue).apply();
                    String command = "picocom " + path + " --baud " + baudValue
                            + " --parity " + parityValue + " --flow " + flowValue
                            + " --databits " + bitsValue + " --stopbits " + stopsValue;
                    dialog.dismiss();
                    openNanoTerminal(command);
                }));
        showTrackedDialog(dialog);
    }

    private Spinner serialChoice(LinearLayout form, String label, String[] values, String selected) {
        form.addView(text(label, 13, MUTED), spaced());
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return deviceTypeLabel(getItem(position), false);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return deviceTypeLabel(getItem(position), true);
            }
        };
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, adapter.getPosition(selected)));
        spinner.setBackground(rounded(SURFACE));
        spinner.setPopupBackgroundDrawable(rounded(PANEL));
        spinner.post(() -> spinner.setDropDownWidth(spinner.getWidth()));
        form.addView(spinner);
        return spinner;
    }

    private void showVideoSettings() {
        PikvmClient active = client;
        if (active == null) return;
        io.execute(() -> {
            try {
                JSONObject state = active.videoSettings();
                runOnUiThread(() -> {
                    if (client == active && !locked) renderVideoSettings(active, state);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (client == active) showTrackedDialog(new AlertDialog.Builder(this)
                            .setTitle("Video settings unavailable")
                            .setMessage(error.getMessage()).setPositiveButton("OK", null).create());
                });
            }
        });
    }

    private void renderVideoSettings(PikvmClient active, JSONObject state) {
        boolean nano = active instanceof NanoKvmClient;
        JSONObject features = state.optJSONObject("features");
        JSONObject params = state.optJSONObject("params");
        JSONObject limits = state.optJSONObject("limits");
        if (params == null) params = new JSONObject();
        if (limits == null) limits = new JSONObject();
        LinearLayout content = column();
        content.setPadding(dp(20), dp(4), dp(20), dp(8));
        content.addView(text(nano
                ? "NanoKVM does not report its current video settings. Values shown here are the last ones you set in this app."
                : "Changes the KVM video stream for all connected viewers. Screen resolution depends on the KVM hardware.",
                13, MUTED));

        if (nano) {
            addVideoChoice(content, active, "Video mode", "mode",
                    java.util.Arrays.asList("h264", "mjpeg"),
                    selectedVideoValue(active, params, "mode"), "");
        }

        int maxFps = nano ? 60 : limits.optJSONObject("desired_fps") != null
                ? limits.optJSONObject("desired_fps").optInt("max", 60) : limits.optInt("max_fps", 60);
        ArrayList<String> fps = new ArrayList<>();
        for (int value : new int[]{15, 24, 30, 40, 60}) {
            if (value <= maxFps) fps.add(Integer.toString(value));
        }
        if (fps.isEmpty()) fps.add(Integer.toString(Math.max(1, maxFps)));
        addVideoChoice(content, active, "Frame rate", "desired_fps", fps,
                selectedVideoValue(active, params, "desired_fps"), " fps");

        if (nano) {
            if (active.hasNativeVideo()) {
                addVideoChoice(content, active, "H.264 bitrate", "quality",
                        java.util.Arrays.asList("1000", "2000", "3000", "5000"),
                        selectedVideoValue(active, params, "quality"), " kbps");
                addVideoChoice(content, active, "GOP", "gop",
                        java.util.Arrays.asList("10", "30", "50", "100"),
                        selectedVideoValue(active, params, "gop"), " frames");
            } else {
                addVideoChoice(content, active, "MJPEG quality", "quality",
                        java.util.Arrays.asList("50", "60", "80", "100"),
                        selectedVideoValue(active, params, "quality"), "%");
            }
        } else if (features != null && features.optBoolean("quality")) {
            addVideoChoice(content, active, "JPEG quality", "quality",
                    java.util.Arrays.asList("40", "60", "80", "100"),
                    selectedVideoValue(active, params, "quality"), "%");
        }

        if (!nano && features != null && features.optBoolean("resolution")) {
            ArrayList<String> resolutions = new ArrayList<>();
            JSONArray supported = limits.optJSONArray("available_resolutions");
            if (supported != null) {
                for (int index = 0; index < supported.length(); index++) {
                    String resolution = supported.optString(index, "");
                    if (resolution.matches("[0-9]+x[0-9]+")) resolutions.add(resolution);
                }
            }
            if (resolutions.isEmpty()) resolutions.addAll(java.util.Arrays.asList(
                    "640x480", "800x600", "1024x768", "1280x720", "1920x1080"));
            String current = selectedVideoValue(active, params, "resolution");
            if (!current.isEmpty() && !resolutions.contains(current)) resolutions.add(0, current);
            addVideoChoice(content, active, "Capture resolution", "resolution", resolutions,
                    current, "");
        } else {
            TextView note = text("Capture resolution is controlled by the KVM or the computer's display settings.",
                    12, MUTED);
            content.addView(note, spaced());
        }
        Button fit = button("Phone display  ·  " + (fillScreen ? "Fill screen" : "Fit whole image"),
                SURFACE, TEXT);
        content.addView(fit, spaced());
        fit.setOnClickListener(view -> {
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("Phone display")
                    .setSingleChoiceItems(new String[]{"Fit whole image", "Fill screen (crop edges)"},
                            fillScreen ? 1 : 0, (choice, index) -> {
                                fillScreen = index == 1;
                                getSharedPreferences("video_preferences", MODE_PRIVATE).edit()
                                        .putBoolean("fill_screen", fillScreen).apply();
                                updateFastVideoTransform();
                                screen.invalidate();
                                choice.dismiss();
                                if (videoSettingsDialog != null) videoSettingsDialog.dismiss();
                                showVideoSettings();
                            }).setNegativeButton("Cancel", null).create());
        });
        addVideoChoice(content, active, "Display scale", "scale",
                java.util.Arrays.asList("50", "75", "100", "150", "200", "300", "400"),
                Integer.toString(screenScalePercent), "%");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Video settings")
                .setView(scroll).setPositiveButton("Done", null).create();
        videoSettingsDialog = dialog;
        showTrackedDialog(dialog, () -> {
            if (videoSettingsDialog == dialog) videoSettingsDialog = null;
        });
    }

    private String selectedVideoValue(PikvmClient active, JSONObject params, String parameter) {
        String reported = params.optString(parameter, "");
        if (!(active instanceof NanoKvmClient) && !reported.isEmpty()) return reported;
        String saved = getSharedPreferences("video_preferences", MODE_PRIVATE)
                .getString(videoPreferenceKey(active, parameter), "");
        if (saved.isEmpty() && active instanceof NanoKvmClient && "mode".equals(parameter)) return "h264";
        if (saved.isEmpty() && active instanceof NanoKvmClient && "quality".equals(parameter)
                && active.hasNativeVideo()) {
            String legacy = getSharedPreferences("video_preferences", MODE_PRIVATE)
                    .getString(active.baseUrl + "|quality", "");
            if (legacy.matches("[0-9]+") && Integer.parseInt(legacy) > 100) return legacy;
        }
        return saved;
    }

    private String videoPreferenceKey(PikvmClient active, String parameter) {
        if (active instanceof NanoKvmClient && "quality".equals(parameter)) {
            parameter = active.hasNativeVideo() ? "quality_h264" : "quality_mjpeg";
        }
        return active.baseUrl + "|" + parameter;
    }

    private void addVideoChoice(LinearLayout content, PikvmClient active, String title,
                                String parameter, List<String> values, String current, String unit) {
        Button button = button(title + "  ·  " + (current.isEmpty() ? "Choose"
                : videoChoiceLabel(parameter, current, unit)), SURFACE, TEXT);
        content.addView(button, spaced());
        button.setOnClickListener(view -> {
            ArrayList<String> choices = new ArrayList<>(values);
            if (!current.isEmpty() && !choices.contains(current)) choices.add(0, current);
            String[] labels = new String[choices.size()];
            int selected = -1;
            for (int index = 0; index < choices.size(); index++) {
                labels[index] = videoChoiceLabel(parameter, choices.get(index), unit);
                if (choices.get(index).equals(current)) selected = index;
            }
            showTrackedDialog(new AlertDialog.Builder(this).setTitle(title)
                    .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                        dialog.dismiss();
                        applyVideoSetting(active, parameter, choices.get(index));
                    }).setNegativeButton("Cancel", null).create());
        });
    }

    private String videoChoiceLabel(String parameter, String value, String unit) {
        if ("mode".equals(parameter)) return "mjpeg".equals(value) ? "MJPEG" : "H.264 Direct";
        return value + unit;
    }

    private void applyVideoSetting(PikvmClient active, String parameter, String value) {
        if ("scale".equals(parameter)) {
            screenScalePercent = Integer.parseInt(value);
            getSharedPreferences("video_preferences", MODE_PRIVATE).edit()
                    .putInt(active.baseUrl + "|scale", screenScalePercent).apply();
            updateFastVideoTransform();
            screen.invalidate();
            if (videoSettingsDialog != null) videoSettingsDialog.dismiss();
            showVideoSettings();
            return;
        }
        io.execute(() -> {
            try {
                active.setVideoParameter(parameter, value);
                getSharedPreferences("video_preferences", MODE_PRIVATE).edit()
                        .putString(videoPreferenceKey(active, parameter), value).apply();
                runOnUiThread(() -> {
                    if (client != active) return;
                    if ("resolution".equals(parameter) || "mode".equals(parameter)) startVideo(active);
                    Toast.makeText(this, "Video setting applied", Toast.LENGTH_SHORT).show();
                    if (videoSettingsDialog != null) videoSettingsDialog.dismiss();
                    showVideoSettings();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (client == active) showTrackedDialog(new AlertDialog.Builder(this)
                            .setTitle("Could not change video setting")
                            .setMessage(error.getMessage()).setPositiveButton("OK", null).create());
                });
            }
        });
    }

    private void showTrackedDialog(AlertDialog dialog) {
        showTrackedDialog(dialog, null);
    }

    private void showTrackedDialog(AlertDialog dialog, Runnable onDismiss) {
        openDialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> {
            openDialogs.remove(dialog);
            if (securitySettingsDialog == dialog) securitySettingsDialog = null;
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();
    }

    private void refreshSecureFlag() {
        if (securityStore.enabled()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void displayLockPage() {
        locked = true;
        closeNanoTerminal(false);
        handler.removeCallbacks(powerPoll);
        stopVideo();
        if (client != null) client.stopEvents();
        View focused = getCurrentFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (focused != null && keyboard != null) {
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        lockPage = new ScrollView(this);
        lockPage.setFillViewport(true);
        lockPage.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(26), dp(36), dp(26), dp(36));
        lockPage.addView(root);
        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_launcher);
        root.addView(mascot, new LinearLayout.LayoutParams(dp(70), dp(70)));
        TextView title = text("Unlock androidKVM", 25, TEXT);
        title.setTypeface(null, 1);
        root.addView(title, spaced());
        root.addView(text("Your saved KVMs are locked.", 14, MUTED), spaced());
        lockStatus = text("", 13, MUTED);
        if (securityStore.hasPin()) {
            lockPinInput = pinField("App PIN");
            lockPinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            lockPinInput.setOnEditorActionListener((view, action, event) -> {
                if (action == EditorInfo.IME_ACTION_DONE) { tryUnlockPin(); return true; }
                return false;
            });
            root.addView(lockPinInput, spaced());
            Button unlock = button("Unlock with PIN", ACCENT, BG);
            unlock.setOnClickListener(view -> tryUnlockPin());
            root.addView(unlock, spaced());
        } else {
            lockPinInput = null;
        }
        if (securityStore.hasBiometrics()) {
            Button useBio = button("Use biometrics", SURFACE, TEXT);
            useBio.setOnClickListener(view -> promptBiometricUnlock());
            root.addView(useBio, spaced());
            if (!securityStore.hasPin()) {
                root.addView(text("Your Android screen lock is available as a fallback.",
                        12, MUTED), spaced());
            }
        }
        root.addView(lockStatus, spaced());
        setContentView(lockPage);
    }

    private void tryUnlockPin() {
        if (!locked || !securityStore.hasPin() || pinCheckBusy) return;
        long wait = securityStore.retrySeconds();
        if (wait > 0) {
            lockStatus.setText("Too many attempts. Try again in " + wait + " seconds.");
            return;
        }
        String candidate = lockPinInput.getText().toString();
        lockPinInput.setText("");
        pinCheckBusy = true;
        lockStatus.setText("Checking PIN…");
        securityIo.execute(() -> {
            try {
                boolean valid = securityStore.verifyPin(candidate);
                if (valid) securityStore.clearFailures();
                else securityStore.recordFailure();
                runOnUiThread(() -> {
                    pinCheckBusy = false;
                    if (!locked) return;
                    if (valid) unlockApp();
                    else {
                        long retry = securityStore.retrySeconds();
                        lockStatus.setText(retry > 0
                                ? "Too many attempts. Try again in " + retry + " seconds."
                                : "Incorrect PIN.");
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    pinCheckBusy = false;
                    if (locked) lockStatus.setText("Could not check PIN: " + error.getMessage());
                });
            }
        });
    }

    private void unlockApp() {
        locked = false;
        securityStore.clearFailures();
        setContentView(resumePage == consolePage && client == null ? loginPage : resumePage);
        if (client != null && resumePage == consolePage) {
            startVideo(client);
            handler.removeCallbacks(powerPoll);
            handler.post(powerPoll);
        }
        lockPage = null;
        lockPinInput = null;
        lockStatus = null;
    }

    private void promptBiometricUnlock() {
        if (!foreground || !locked || !securityStore.hasBiometrics() || promptBusy) return;
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK;
        if (!securityStore.hasPin()) authenticators |= BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        if (BiometricManager.from(this).canAuthenticate(authenticators)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            if (lockStatus != null) lockStatus.setText("Biometrics are unavailable on this device.");
            return;
        }
        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock androidKVM")
                .setAllowedAuthenticators(authenticators);
        if (securityStore.hasPin()) info.setNegativeButtonText("Use app PIN");
        promptBusy = true;
        enablingBiometrics = false;
        biometricPrompt.authenticate(info.build());
    }

    private void showSecuritySettings() {
        if (locked) return;
        if (securitySettingsDialog != null) securitySettingsDialog.dismiss();
        LinearLayout content = panel();
        boolean pin = securityStore.hasPin();
        boolean bio = securityStore.hasBiometrics();
        content.addView(text(!pin && !bio ? "App lock is off."
                : pin && bio ? "Unlock with your app PIN or biometrics."
                : pin ? "Unlock with your app PIN."
                : "Unlock with biometrics or your Android screen lock.", 14, TEXT));
        content.addView(text("The lock appears whenever you leave and reopen the app.",
                12, MUTED), spaced());
        Button setPin = button(pin ? "Change PIN" : "Set app PIN", SURFACE, TEXT);
        setPin.setOnClickListener(view -> showPinSetup());
        content.addView(setPin, spaced());
        if (pin) {
            Button removePin = button("Remove PIN", SURFACE, MUTED);
            removePin.setOnClickListener(view -> showRemovePin());
            content.addView(removePin, spaced());
        }
        Button biometrics = button(bio ? "Turn off biometrics" : "Turn on biometrics", SURFACE, TEXT);
        biometrics.setOnClickListener(view -> {
            if (bio) {
                try {
                    securityStore.setBiometrics(false);
                    refreshSecureFlag();
                    showSecuritySettings();
                } catch (Exception error) {
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else enableBiometrics();
        });
        content.addView(biometrics, spaced());
        securitySettingsDialog = new AlertDialog.Builder(this).setTitle("Security settings")
                .setView(content).setPositiveButton("Done", null).create();
        showTrackedDialog(securitySettingsDialog);
    }

    private EditText pinField(String hint) {
        EditText input = field(hint, true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        return input;
    }

    private void showPinSetup() {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText old = securityStore.hasPin() ? pinField("Current PIN") : null;
        if (old != null) form.addView(old, spaced());
        EditText pin = pinField("New PIN (6 to 12 digits)");
        EditText repeat = pinField("Confirm new PIN");
        form.addView(pin, spaced());
        form.addView(repeat, spaced());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(old == null ? "Set app PIN" : "Change app PIN")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        if (old != null && !securityStore.verifyPin(old.getText().toString())) {
                            old.setError("Current PIN is incorrect.");
                            return;
                        }
                        String value = pin.getText().toString();
                        if (!value.equals(repeat.getText().toString())) {
                            repeat.setError("PINs do not match.");
                            return;
                        }
                        securityStore.setPin(value);
                        refreshSecureFlag();
                        dialog.dismiss();
                        showSecuritySettings();
                    } catch (Exception error) {
                        pin.setError(error.getMessage());
                    }
                }));
        showTrackedDialog(dialog);
    }

    private void showRemovePin() {
        EditText current = pinField("Current PIN");
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(text(securityStore.hasBiometrics()
                ? "Biometrics will still unlock the app."
                : "This will turn off the app lock.", 13, MUTED));
        form.addView(current, spaced());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Remove app PIN")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Remove", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        if (!securityStore.verifyPin(current.getText().toString())) {
                            current.setError("Current PIN is incorrect.");
                            return;
                        }
                        securityStore.removePin();
                        refreshSecureFlag();
                        dialog.dismiss();
                        showSecuritySettings();
                    } catch (Exception error) {
                        current.setError(error.getMessage());
                    }
                }));
        showTrackedDialog(dialog);
    }

    private void enableBiometrics() {
        if (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            showTrackedDialog(new AlertDialog.Builder(this).setTitle("Biometrics unavailable")
                    .setMessage("Set up fingerprint or face unlock in Android Settings, then try again.")
                    .setPositiveButton("OK", null).create());
            return;
        }
        enablingBiometrics = true;
        promptBusy = true;
        biometricPrompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable biometrics for androidKVM")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Cancel").build());
    }

    private void buildUi() {
        loginPage = new ScrollView(this);
        loginPage.setFillViewport(true);
        loginPage.setBackgroundColor(BG);
        loginPage.setOnApplyWindowInsetsListener((view, insets) -> {
            view.post(this::updateLoginKeyboardInset);
            return insets;
        });
        loginPage.getViewTreeObserver().addOnGlobalLayoutListener(this::updateLoginKeyboardInset);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(12), dp(18), dp(24));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).top : insets.getSystemWindowInsetTop();
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom : insets.getSystemWindowInsetBottom();
            view.setPadding(dp(18), dp(12) + top, dp(18), dp(24) + bottom);
            return insets;
        });
        loginPage.addView(root);
        setContentView(loginPage);
        resumePage = loginPage;

        LinearLayout brand = row();
        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_launcher);
        mascot.setContentDescription("androidKVM raccoon logo");
        brand.addView(mascot, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout brandText = column();
        brandText.setPadding(dp(10), 0, 0, 0);
        TextView title = text("androidKVM", 24, TEXT);
        title.setTypeface(null, 1);
        brandText.addView(title);
        brandText.addView(text("Your KVMs, in your pocket", 12, MUTED));
        brand.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
        Button homeMenu = compactButton("⋮");
        homeMenu.setTextSize(28);
        homeMenu.setBackground(rounded(PANEL));
        homeMenu.setContentDescription("More options");
        homeMenu.setOnClickListener(this::showOverflow);
        brand.addView(homeMenu, new LinearLayout.LayoutParams(dp(44), dp(48)));
        root.addView(brand);
        LinearLayout tabs = row();
        kvmTab = button("KVMs", ACCENT, BG);
        updatesTab = button("Updates", PANEL, TEXT);
        tabs.addView(kvmTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams updateTabSize = new LinearLayout.LayoutParams(0, dp(48), 1);
        updateTabSize.leftMargin = dp(8);
        tabs.addView(updatesTab, updateTabSize);
        root.addView(tabs, spaced());
        kvmHome = column();
        root.addView(kvmHome);
        updatesHome = column();
        updatesHome.setVisibility(View.GONE);
        root.addView(updatesHome);
        kvmTab.setOnClickListener(view -> showHomeTab(false));
        updatesTab.setOnClickListener(view -> showHomeTab(true));
        sectionTitle = text("MY KVMS", 12, ACCENT);
        sectionTitle.setTypeface(null, 1);
        kvmHome.addView(sectionTitle, spaced());

        savedPanel = column();
        kvmHome.addView(savedPanel, spaced());
        addKvmButton = button("＋  Add KVM", PANEL, ACCENT);
        addKvmButton.setOnClickListener(view -> {
            prepareNewKvmForm();
            savedPanel.setVisibility(View.GONE);
            sectionTitle.setText("ADD A KVM");
            loginPanel.setVisibility(View.VISIBLE);
            addKvmButton.setVisibility(View.GONE);
            loginPage.post(() -> loginPage.smoothScrollTo(0, 0));
        });
        kvmHome.addView(addKvmButton, spaced());

        loginPanel = panel();
        kvmHome.addView(loginPanel, spaced());
        LinearLayout formHeader = row();
        TextView formTitle = text("Connect a KVM", 19, TEXT);
        formTitle.setTypeface(null, 1);
        formHeader.addView(formTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button closeForm = compactButton("×");
        closeFormButton = closeForm;
        closeForm.setContentDescription("Close Add KVM form");
        closeForm.setOnClickListener(view -> closeAddKvmForm());
        formHeader.addView(closeForm, new LinearLayout.LayoutParams(dp(44), dp(44)));
        closeForm.setTextSize(26);
        closeForm.setTypeface(null, 1);
        closeForm.setBackground(rounded(SURFACE));
        closeForm.setTextColor(TEXT);
        loginPanel.addView(formHeader);
        loginPanel.addView(text("Use the login from your KVM web page.", 13, MUTED));
        deviceTypeInput = button("KVM type  ·  Choose  ▾", SURFACE, TEXT);
        deviceTypeInput.setOnClickListener(view -> showTrackedDialog(new AlertDialog.Builder(this)
                .setTitle("KVM type")
                .setSingleChoiceItems(new String[]{"PiKVM", "NanoKVM"},
                        "nanokvm".equals(selectedDeviceType) ? 1 : 0, (dialog, index) -> {
                            setDeviceType(index == 1 ? "nanokvm" : "pikvm");
                            dialog.dismiss();
                        })
                .setNegativeButton("Cancel", null).create()));
        loginPanel.addView(deviceTypeInput, spaced());
        addressInput = field("KVM address or URL", false);
        userInput = field("Username", false);
        passwordInput = field("Password", true);
        trustedAddress = getPreferences(0).getString("address", "");
        trustedFingerprint = getPreferences(0).getString("pin", "");
        setDeviceType("");
        for (EditText input : new EditText[]{addressInput, userInput, passwordInput}) {
            input.setOnFocusChangeListener((view, focused) -> {
                if (focused) loginPage.post(this::updateLoginKeyboardInset);
            });
            loginPanel.addView(input, spaced());
        }
        rememberInput = new CheckBox(this);
        rememberInput.setText("Save this KVM and password on this phone");
        rememberInput.setTextColor(TEXT);
        rememberInput.setChecked(true);
        loginPanel.addView(rememberInput, spaced());
        loginPanel.addView(text("NanoKVM uses its own address. For HTTP-only NanoKVMs, enter http:// explicitly; HTTP sends credentials without encryption. For PiKVM 2FA, use a fresh code.", 12, MUTED), spaced());
        loginButton = button("Connect", ACCENT, BG);
        loginButton.setOnClickListener(view -> login());
        loginPanel.addView(loginButton, spaced());

        message = text("", 13, MUTED);
        message.setMinHeight(dp(30));
        kvmHome.addView(message, spaced());
        renderSavedProfiles();

        LinearLayout updatePanel = panel();
        updatesHome.addView(updatePanel, spaced());
        TextView updateTitle = text("App updates", 20, TEXT);
        updateTitle.setTypeface(null, 1);
        updatePanel.addView(updateTitle);
        updatePanel.addView(text("Installed: " + BuildConfig.VERSION_NAME, 13, MUTED), spaced());
        updatePanel.addView(text("Source: github.com/Sergi095/androidKVM", 13, MUTED), spaced());
        updateStatus = text("Check for a signed APK from the project's GitHub Releases.", 14, TEXT);
        updatePanel.addView(updateStatus, spaced());
        checkUpdateButton = button("Check for updates", SURFACE, TEXT);
        checkUpdateButton.setOnClickListener(view -> checkForUpdates());
        updatePanel.addView(checkUpdateButton, spaced());
        installUpdateButton = button("Download and install", ACCENT, BG);
        installUpdateButton.setVisibility(View.GONE);
        installUpdateButton.setOnClickListener(view -> downloadOrInstallUpdate());
        updatePanel.addView(installUpdateButton, spaced());

        consolePage = column();
        consolePage.setBackgroundColor(CONSOLE_BG);
        consolePage.setPadding(0, 0, 0, 0);
        consolePage.setOnApplyWindowInsetsListener((view, insets) -> {
            consoleTopInset = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).top : insets.getSystemWindowInsetTop();
            consoleBottomInset = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom : insets.getSystemWindowInsetBottom();
            view.post(this::updateConsoleKeyboardInset);
            return insets;
        });
        consolePage.getViewTreeObserver().addOnGlobalLayoutListener(this::updateConsoleKeyboardInset);
        LinearLayout toolbar = row();
        toolbar.setPadding(dp(6), 0, dp(6), 0);
        toolbar.setBackgroundColor(CONSOLE_BG);
        Button disconnect = compactButton("‹");
        disconnect.setTextSize(29);
        disconnect.setContentDescription("Close terminal or disconnect KVM");
        disconnect.setOnClickListener(view -> {
            if (terminalPanel != null) closeNanoTerminal(true);
            else logout();
        });
        toolbar.addView(disconnect, new LinearLayout.LayoutParams(dp(42), dp(46)));
        consoleTitle = text("KVM console", 13, TEXT);
        consoleTitle.setTypeface(null, 1);
        consoleTitle.setSingleLine(true);
        consoleTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        consoleTitle.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(consoleTitle, new LinearLayout.LayoutParams(0, dp(46), 1));
        remoteInput = new RemoteInput(this);
        toolbar.addView(remoteInput, new LinearLayout.LayoutParams(dp(1), dp(1)));
        Button mouse = compactButton("◎");
        mouse.setTextSize(24);
        mouse.setContentDescription("Choose touch or trackpad mouse mode");
        mouse.setOnClickListener(view -> showMouseModeDialog());
        toolbar.addView(mouse, new LinearLayout.LayoutParams(dp(42), dp(46)));
        Button keyboard = compactButton("⌨");
        keyboard.setContentDescription("Show or hide Android keyboard");
        keyboard.setOnClickListener(view -> toggleKeyboard());
        toolbar.addView(keyboard, new LinearLayout.LayoutParams(dp(42), dp(46)));
        ImageButton power = new ImageButton(this);
        power.setImageResource(R.drawable.ic_power);
        power.setBackgroundColor(CONSOLE_BG);
        power.setPadding(dp(10), dp(10), dp(10), dp(10));
        power.setFocusable(false);
        power.setContentDescription("Power controls");
        power.setOnClickListener(view -> showPowerDialog());
        toolbar.addView(power, new LinearLayout.LayoutParams(dp(42), dp(46)));
        Button consoleMenu = compactButton("⋮");
        consoleMenu.setTextSize(25);
        consoleMenu.setContentDescription("More options");
        consoleMenu.setOnClickListener(this::showOverflow);
        toolbar.addView(consoleMenu, new LinearLayout.LayoutParams(dp(38), dp(46)));
        consolePage.addView(toolbar);

        videoArea = new FrameLayout(this);
        videoArea.setBackgroundColor(Color.BLACK);
        fastVideo = new TextureView(this);
        fastVideo.setVisibility(View.GONE);
        fastVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                if (client != null && client.hasNativeVideo()) startNativeVideo(client);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                updateFastVideoTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (h264Reader != null) { h264Reader.stop(); h264Reader = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        videoArea.addView(fastVideo, new FrameLayout.LayoutParams(-1, -1));
        screen = new RemoteScreen(this);
        screen.setContentDescription("Remote screen. Tap to click, double tap to double click, hold for right click, or pinch to zoom.");
        videoArea.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        consolePage.addView(videoArea, new LinearLayout.LayoutParams(-1, 0, 1));

        videoStatus = text("Connecting to video…", 11, MUTED);
        videoStatus.setPadding(dp(8), 0, dp(8), 0);
        consolePage.addView(videoStatus, new LinearLayout.LayoutParams(-1, dp(18)));

        extraKeys = column();
        extraKeys.setBackgroundColor(CONSOLE_BG);
        extraKeys.addView(compactKeyRow(ExtraKeyLayout.FIRST));
        extraKeys.addView(compactKeyRow(ExtraKeyLayout.SECOND));
        extraKeys.addView(keyRow(ExtraKeyLayout.FUNCTIONS));
        consolePage.addView(extraKeys);

    }

    private void showHomeTab(boolean updates) {
        kvmHome.setVisibility(updates ? View.GONE : View.VISIBLE);
        updatesHome.setVisibility(updates ? View.VISIBLE : View.GONE);
        kvmTab.setBackground(rounded(updates ? PANEL : ACCENT));
        kvmTab.setTextColor(updates ? TEXT : BG);
        updatesTab.setBackground(rounded(updates ? ACCENT : PANEL));
        updatesTab.setTextColor(updates ? BG : TEXT);
        loginPage.smoothScrollTo(0, 0);
        if (updates && latestRelease == null && !updateBusy) checkForUpdates();
    }

    private void checkForUpdates() {
        if (updateBusy) return;
        updateBusy = true;
        latestRelease = null;
        pendingInstall = null;
        checkUpdateButton.setEnabled(false);
        installUpdateButton.setVisibility(View.GONE);
        updateStatus.setText("Checking GitHub Releases…");
        updatesIo.execute(() -> {
            try {
                UpdateManager.Release release = updateManager.latest();
                boolean newer = updateManager.newerThanInstalled(release);
                runOnUiThread(() -> {
                    updateBusy = false;
                    checkUpdateButton.setEnabled(true);
                    latestRelease = release;
                    updateStatus.setText(newer
                            ? "Version " + release.version + " is available (" + (release.size / 1024) + " KB)."
                            : "You're up to date. Latest release: " + release.version + ".");
                    installUpdateButton.setVisibility(newer ? View.VISIBLE : View.GONE);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateBusy = false;
                    checkUpdateButton.setEnabled(true);
                    updateStatus.setText("Could not check for updates: " + error.getMessage());
                });
            }
        });
    }

    private void downloadOrInstallUpdate() {
        if (pendingInstall != null && pendingInstall.isFile()) {
            installDownloadedUpdate();
            return;
        }
        UpdateManager.Release release = latestRelease;
        if (release == null || updateBusy || !updateManager.newerThanInstalled(release)) return;
        updateBusy = true;
        checkUpdateButton.setEnabled(false);
        installUpdateButton.setEnabled(false);
        updateStatus.setText("Downloading from GitHub…");
        updatesIo.execute(() -> {
            try {
                File apk = updateManager.download(release, percent -> runOnUiThread(() ->
                        updateStatus.setText("Downloading from GitHub… " + percent + "%")));
                runOnUiThread(() -> {
                    updateBusy = false;
                    checkUpdateButton.setEnabled(true);
                    installUpdateButton.setEnabled(true);
                    installUpdateButton.setText("Install downloaded APK");
                    pendingInstall = apk;
                    updateStatus.setText("Version " + release.version + " downloaded and verified.");
                    installDownloadedUpdate();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateBusy = false;
                    checkUpdateButton.setEnabled(true);
                    installUpdateButton.setEnabled(true);
                    updateStatus.setText("Update failed: " + error.getMessage());
                });
            }
        });
    }

    private void installDownloadedUpdate() {
        if (pendingInstall == null || !pendingInstall.isFile()) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            updateStatus.setText("Allow androidKVM to install apps, then return to finish the update.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            try { startActivityForResult(settings, INSTALL_PERMISSION_REQUEST); }
            catch (Exception error) { updateStatus.setText("Open Android settings and allow installs from androidKVM."); }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", pendingInstall);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.setClipData(ClipData.newRawUri("androidKVM update", uri));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
            updateStatus.setText("Confirm the update in Android's installer.");
        } catch (Exception error) {
            updateStatus.setText("Could not open Android's installer: " + error.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INSTALL_PERMISSION_REQUEST && getPackageManager().canRequestPackageInstalls())
            installDownloadedUpdate();
    }

    private void updateLoginKeyboardInset() {
        if (loginPage == null || !loginPage.isAttachedToWindow() || loginPage.getHeight() == 0) return;
        Rect visible = new Rect();
        loginPage.getWindowVisibleDisplayFrame(visible);
        int[] position = new int[2];
        loginPage.getLocationOnScreen(position);
        int contentBottom = position[1] + loginPage.getHeight();
        int covered = Math.max(0, contentBottom - visible.bottom);
        WindowInsets insets = loginPage.getRootWindowInsets();
        Point display = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(display);
        boolean keyboardShown = display.y - visible.bottom > dp(160);
        int keyboardTop = visible.bottom;
        if (Build.VERSION.SDK_INT >= 30 && insets != null
                && insets.isVisible(WindowInsets.Type.ime())) {
            keyboardShown = true;
            keyboardTop = display.y - insets.getInsets(WindowInsets.Type.ime()).bottom;
            covered = Math.max(covered, Math.max(0, contentBottom - keyboardTop));
        }
        if (loginPage.getPaddingBottom() != covered) loginPage.setPadding(0, 0, 0, covered);
        if (!keyboardShown || loginPanel == null || loginPanel.getVisibility() != View.VISIBLE) return;
        View focused = getCurrentFocus();
        if (focused != addressInput && focused != userInput && focused != passwordInput) return;
        int[] fieldPosition = new int[2];
        focused.getLocationOnScreen(fieldPosition);
        int viewportBottom = Math.min(contentBottom - covered,
                Math.min(visible.bottom, keyboardTop));
        int below = fieldPosition[1] + focused.getHeight() + dp(12) - viewportBottom;
        if (below > 0) loginPage.scrollBy(0, below);
        else if (fieldPosition[1] < position[1] + dp(12))
            loginPage.scrollBy(0, fieldPosition[1] - position[1] - dp(12));
    }

    private void updateConsoleKeyboardInset() {
        if (consolePage == null || !consolePage.isAttachedToWindow() || consolePage.getHeight() == 0) return;
        Rect visible = new Rect();
        consolePage.getWindowVisibleDisplayFrame(visible);
        WindowInsets insets = consolePage.getRootWindowInsets();
        boolean keyboardShown = isConsoleKeyboardShown(insets, visible);
        if (extraKeys != null && extraKeys.getVisibility() != (keyboardShown ? View.VISIBLE : View.GONE))
            extraKeys.setVisibility(keyboardShown ? View.VISIBLE : View.GONE);
        int[] position = new int[2];
        consolePage.getLocationOnScreen(position);
        int contentBottom = position[1] + consolePage.getHeight();
        int covered = Math.max(0, contentBottom - visible.bottom);
        if (Build.VERSION.SDK_INT >= 30) {
            if (insets != null && keyboardShown) {
                Point display = new Point();
                getWindowManager().getDefaultDisplay().getRealSize(display);
                int keyboardTop = display.y - insets.getInsets(WindowInsets.Type.ime()).bottom;
                covered = Math.max(covered, Math.max(0, contentBottom - keyboardTop));
            }
        }
        int bottom = Math.max(consoleBottomInset, covered);
        if (consolePage.getPaddingTop() != consoleTopInset || consolePage.getPaddingBottom() != bottom) {
            consolePage.setPadding(0, consoleTopInset, 0, bottom);
        }
    }

    private boolean isConsoleKeyboardShown(WindowInsets insets, Rect visible) {
        if (Build.VERSION.SDK_INT >= 30 && insets != null)
            return insets.isVisible(WindowInsets.Type.ime());
        return consolePage.getRootView().getHeight() - visible.bottom > dp(160);
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
        if (code.equals("MetaLeft")) {
            modifierButtons.add(key);
            key.setTag(new String[]{label, code});
            key.setContentDescription("Windows or Command key. Tap to press; hold to use with the next key.");
            key.setOnClickListener(view -> {
                if (terminalPanel != null || modifiers.contains(code)) toggleModifier(code);
                else sendKey(code);
            });
            key.setOnLongClickListener(view -> {
                toggleModifier(code);
                key.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                return true;
            });
        } else if (code.equals("ControlLeft") || code.equals("AltLeft") || code.equals("ShiftLeft")) {
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
        offButton.setOnClickListener(view -> showTrackedDialog(new AlertDialog.Builder(this)
                .setTitle("Shut down remote computer?")
                .setMessage("This presses its power button briefly.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Shut down", (dialog, which) -> setPower(false))
                .create()));
        ProfileStore.Profile wolProfile = activeWakeProfile();
        Button wake = button("Wake via WOL", SURFACE, KEY_ACCENT);
        TextView wakeStatus = text(wolProfile == null
                ? "Set up WOL on this KVM's saved card to enable it."
                : "The KVM sends the wake packet on its own LAN.", 12, MUTED);
        wake.setEnabled(wolProfile != null);
        wake.setAlpha(wolProfile != null ? 1f : 0.45f);
        if (wolProfile != null) wake.setOnClickListener(view -> sendWakeOnLan(wolProfile, wake, wakeStatus));
        content.addView(wake, spaced());
        content.addView(wakeStatus, spaced());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Power")
                .setView(content)
                .setNegativeButton("Close", null)
                .create();
        showTrackedDialog(dialog, () -> {
            powerStatus = null;
            onButton = null;
            offButton = null;
        });
        refreshPower();
    }

    private void toggleKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard == null) return;
        Rect visible = new Rect();
        consolePage.getWindowVisibleDisplayFrame(visible);
        WindowInsets insets = consolePage.getRootWindowInsets();
        boolean shown = isConsoleKeyboardShown(insets, visible);
        if (shown) {
            View focused = terminalPanel == null ? remoteInput : getCurrentFocus();
            if (focused != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        } else {
            if (terminalPanel != null) {
                terminalPanel.showKeyboard();
                return;
            }
            remoteInput.requestFocus();
            keyboard.restartInput(remoteInput);
            remoteInput.post(() -> keyboard.showSoftInput(remoteInput, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private void showMouseModeDialog() {
        showTrackedDialog(new AlertDialog.Builder(this)
                .setTitle("Mouse control")
                .setSingleChoiceItems(new String[]{"Touch screen", "Trackpad"}, trackpadMode ? 1 : 0,
                        (dialog, choice) -> {
                            trackpadMode = choice == 1;
                            screen.resetGesture();
                            Toast.makeText(this, trackpadMode ? "Trackpad mode" : "Touch mode",
                                    Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        })
                .setNegativeButton("Cancel", null)
                .create());
    }

    private void resetKeyboardComposition() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.restartInput(remoteInput);
    }

    private void renderSavedProfiles() {
        savedPanel.removeAllViews();
        List<ProfileStore.Profile> profiles = profileStore.list();
        if (sectionTitle != null) sectionTitle.setText(profiles.isEmpty() ? "ADD YOUR FIRST KVM" : "MY KVMS");
        savedPanel.setVisibility(profiles.isEmpty() ? View.GONE : View.VISIBLE);
        for (ProfileStore.Profile profile : profiles) {
            LinearLayout item = panel();
            LinearLayout content = row();
            TextView device = text("▣", 27, ACCENT);
            device.setGravity(Gravity.CENTER);
            device.setBackground(rounded(BG));
            content.addView(device, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout detail = column();
            detail.setPadding(dp(12), 0, 0, 0);
            TextView address = text(profile.address, 15, TEXT);
            address.setTypeface(null, 1);
            address.setSingleLine(true);
            address.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.addView(address);
            detail.addView(text(("nanokvm".equals(profile.type) ? "NanoKVM" : "PiKVM") + "  ·  " + profile.user + (profile.wolMac.isEmpty()
                    ? "  ·  Saved connection" : "  ·  Wake-on-LAN ready"), 12, MUTED));
            content.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(content);
            LinearLayout actions = row();
            Button connect = button("Connect  →", ACCENT, BG);
            connect.setOnClickListener(view -> connectSavedProfile(profile));
            actions.addView(connect, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button remove = button("Remove", SURFACE, MUTED);
            remove.setTextSize(12);
            remove.setContentDescription("Remove saved KVM " + profile.address);
            LinearLayout.LayoutParams removeSize = new LinearLayout.LayoutParams(dp(84), dp(44));
            removeSize.leftMargin = dp(8);
            actions.addView(remove, removeSize);
            remove.setOnClickListener(view -> showTrackedDialog(new AlertDialog.Builder(this)
                    .setTitle("Remove saved KVM?")
                    .setMessage(profile.address + " (" + profile.user + ")")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> {
                        try {
                            profileStore.remove(profile.id);
                            if (profile.address.equals(trustedAddress)) {
                                trustedAddress = "";
                                trustedFingerprint = "";
                                getPreferences(0).edit().remove("pin").apply();
                            }
                            passwordInput.setText("");
                            renderSavedProfiles();
                        } catch (Exception error) {
                            showMessage("Could not remove saved KVM.");
                        }
                    }).create()));
            item.addView(actions, spaced());
            if (profile.wolMac.isEmpty()) {
                Button setupWol = button("Set up Wake-on-LAN", SURFACE, TEXT);
                setupWol.setOnClickListener(view -> showWakeOnLanDialog(profile));
                item.addView(setupWol, spaced());
            } else {
                Button wolSettings = button("WOL settings", SURFACE, KEY_ACCENT);
                wolSettings.setOnClickListener(view -> showWakeOnLanDialog(profile));
                item.addView(wolSettings, spaced());
            }
            savedPanel.addView(item, spaced());
        }
        if (loginPanel != null) loginPanel.setVisibility(profiles.isEmpty() ? View.VISIBLE : View.GONE);
        if (addKvmButton != null) addKvmButton.setVisibility(profiles.isEmpty() ? View.GONE : View.VISIBLE);
        if (closeFormButton != null) closeFormButton.setVisibility(profiles.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showWakeOnLanDialog(ProfileStore.Profile profile) {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        boolean nano = "nanokvm".equals(profile.type);
        form.addView(text("Enter the computer's wired network MAC, not the KVM's. "
                + (nano ? "NanoKVM sends the packet on its own LAN."
                        : "PiKVM needs its WOL GPIO output configured."), 13, MUTED));
        EditText mac = field("Computer MAC, e.g. AA:BB:CC:DD:EE:FF", false);
        mac.setText(profile.wolMac);
        form.addView(mac, spaced());
        EditText gpioChannel = field("PiKVM WOL GPIO channel (optional)", false);
        gpioChannel.setText(profile.wolGpioChannel);
        if (!nano) {
            form.addView(gpioChannel, spaced());
            form.addView(text("Leave the channel blank to detect a WOL-named PiKVM output. "
                    + "If PiKVM has several WOL outputs, enter the channel for this computer.",
                    12, MUTED), spaced());
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Wake-on-LAN · " + profile.address)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null);
        if (!profile.wolMac.isEmpty()) {
            builder.setNeutralButton("Disable", (dialog, which) -> {
                try {
                    profileStore.setWakeOnLan(profile.id, "", "");
                    renderSavedProfiles();
                } catch (Exception error) {
                    Toast.makeText(this, "Could not remove WOL settings.", Toast.LENGTH_LONG).show();
                }
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String normalizedMac = WakeOnLan.canonicalMac(mac.getText().toString());
                        String channel = nano ? "" : gpioChannel.getText().toString().trim();
                        if (!channel.isEmpty() && !channel.matches("[A-Za-z0-9_]{1,64}")) {
                            throw new IllegalArgumentException("PiKVM channel must use letters, numbers, or underscores.");
                        }
                        profileStore.setWakeOnLan(profile.id, normalizedMac, channel);
                        renderSavedProfiles();
                        dialog.dismiss();
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Could not save WOL settings.", Toast.LENGTH_LONG).show();
                    }
                }));
        showTrackedDialog(dialog);
    }

    private ProfileStore.Profile activeWakeProfile() {
        for (ProfileStore.Profile profile : profileStore.list()) {
            if (profile.type.equals(activeType) && profile.address.equals(activeAddress) && profile.user.equals(activeUser)
                    && !profile.wolMac.isEmpty()) return profile;
        }
        return null;
    }

    private void sendWakeOnLan(ProfileStore.Profile profile, Button wakeButton, TextView status) {
        wakeButton.setEnabled(false);
        status.setText("Asking KVM to send wake packet…");
        wolIo.execute(() -> {
            try {
                PikvmClient active = client;
                if (active == null) throw new IllegalStateException("KVM is disconnected.");
                String result = active.wakeFromKvm(profile.wolMac, profile.wolGpioChannel);
                runOnUiThread(() -> {
                    wakeButton.setEnabled(true);
                    status.setText(result);
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    wakeButton.setEnabled(true);
                    status.setText("Wake-on-LAN failed: " + error.getMessage());
                    Toast.makeText(this, "Wake-on-LAN failed: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void connectSavedProfile(ProfileStore.Profile profile) {
        setDeviceType(profile.type);
        addressInput.setText(profile.address);
        userInput.setText(profile.user);
        trustedAddress = profile.address;
        trustedFingerprint = profile.pin;
        try {
            passwordInput.setText(profileStore.password(profile));
            rememberInput.setChecked(true);
            login(profile.type);
        } catch (Exception error) {
            passwordInput.setText("");
            showMessage("Saved password is unavailable. Enter it again to reconnect.");
        }
    }

    private void prepareNewKvmForm() {
        setDeviceType("");
        addressInput.setText("");
        userInput.setText("");
        passwordInput.setText("");
        rememberInput.setChecked(true);
    }

    private void closeAddKvmForm() {
        if (profileStore.list().isEmpty()) return;
        View focused = getCurrentFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && focused != null)
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        if (focused != null) focused.clearFocus();
        renderSavedProfiles();
        loginPage.post(() -> loginPage.smoothScrollTo(0, 0));
    }

    private void setDeviceType(String type) {
        selectedDeviceType = "nanokvm".equals(type) ? "nanokvm"
                : "pikvm".equals(type) ? "pikvm" : "";
        deviceTypeInput.setText("KVM type  ·  "
                + ("nanokvm".equals(selectedDeviceType) ? "NanoKVM"
                : "pikvm".equals(selectedDeviceType) ? "PiKVM" : "Choose") + "  ▾");
    }

    private void login() {
        if (selectedDeviceType.isEmpty()) {
            showMessage("Choose PiKVM or NanoKVM first.");
            return;
        }
        login(selectedDeviceType);
    }

    private void login(String type) {
        String address = addressInput.getText().toString().trim();
        String user = userInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String pin = address.equals(trustedAddress) && !address.startsWith("http://") ? trustedFingerprint : "";
        boolean remember = rememberInput.isChecked();
        if (address.isEmpty() || user.isEmpty() || password.isEmpty()) {
            showMessage("Enter the KVM address, username, and password.");
            return;
        }
        loginButton.setEnabled(false);
        showMessage("Connecting to " + ("nanokvm".equals(type) ? "NanoKVM" : "PiKVM") + "…");
        io.execute(() -> {
            try {
                PikvmClient next = "nanokvm".equals(type)
                        ? new NanoKvmClient(address, pin) : new PikvmClient(address, pin);
                next.login(user, password);
                if (next instanceof NanoKvmClient) {
                    String savedMode = getSharedPreferences("video_preferences", MODE_PRIVATE)
                            .getString(next.baseUrl + "|mode", "h264");
                    ((NanoKvmClient) next).restoreVideoMode(savedMode);
                }
                String saveError = null;
                if (remember) {
                    try { profileStore.save(type, address, user, pin, password); }
                    catch (Exception error) { saveError = "Connected, but could not save this KVM."; }
                }
                String finalSaveError = saveError;
                runOnUiThread(() -> {
                    client = next;
                    screenScalePercent = getSharedPreferences("video_preferences", MODE_PRIVATE)
                            .getInt(next.baseUrl + "|scale", 100);
                    videoOffsetX = videoOffsetY = 0f;
                    activeAddress = address;
                    activeUser = user;
                    activeType = type;
                    consoleTitle.setText(address);
                    if (remember) {
                        getPreferences(0).edit().putString("address", address).putString("user", user)
                                .putString("type", type)
                                .putString("pin", pin).apply();
                    } else {
                        getPreferences(0).edit().clear().apply();
                    }
                    passwordInput.setText("");
                    renderSavedProfiles();
                    resumePage = consolePage;
                    if (!locked) setContentView(consolePage);
                    loginButton.setEnabled(true);
                    showMessage(finalSaveError == null ? "Connected" : finalSaveError);
                    if (finalSaveError != null) Toast.makeText(this, finalSaveError, Toast.LENGTH_LONG).show();
                    if (!locked) {
                        startVideo(next);
                        handler.removeCallbacks(powerPoll);
                        handler.post(powerPoll);
                    }
                    if (!locked) remoteInput.postDelayed(() -> {
                        remoteInput.requestFocus();
                        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (keyboard != null) keyboard.showSoftInput(remoteInput, InputMethodManager.SHOW_IMPLICIT);
                    }, 150);
                });
            } catch (Exception error) {
                if (certificateError(error)) {
                    try {
                        String fingerprint = PikvmClient.certificateFingerprint(address);
                        runOnUiThread(() -> showCertificateDialog(type, address, fingerprint, !pin.isEmpty()));
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

    private void showCertificateDialog(String type, String address, String fingerprint, boolean changed) {
        if (locked) return;
        loginButton.setEnabled(true);
        showMessage("Trust confirmation required");
        showTrackedDialog(new AlertDialog.Builder(this)
                .setTitle(changed ? "KVM certificate changed" : "Trust this KVM?")
                .setMessage(changed
                        ? "The certificate for " + address + " changed since you last trusted it. Continue only if you expect this change."
                        : "Android does not recognize the certificate for " + address
                                + ". Trust this KVM to connect? Your choice is remembered when you save the KVM.")
                .setNegativeButton("Cancel", (dialog, which) -> showMessage("Connection cancelled"))
                .setPositiveButton("Trust and connect", (dialog, which) -> {
                    trustedAddress = address;
                    trustedFingerprint = fingerprint;
                    login(type);
                })
                .create());
    }

    private void startVideo(PikvmClient active) {
        stopVideo();
        active.ensureEvents();
        currentFrame = null;
        videoWidth = videoHeight = 0;
        videoOffsetX = videoOffsetY = 0f;
        screen.invalidate();
        if (active.hasNativeVideo()) {
            videoStatus.setText("Connecting to H.264 video…");
            fastVideo.setVisibility(View.VISIBLE);
            fastVideo.post(() -> {
                if (client == active && fastVideo.isAvailable()) startNativeVideo(active);
            });
        } else {
            startMjpegVideo(active);
        }
    }

    private void startNativeVideo(PikvmClient active) {
        if (h264Reader != null || client != active || !fastVideo.isAvailable()) return;
        Surface surface = new Surface(fastVideo.getSurfaceTexture());
        h264Reader = new H264Reader(active, surface, new H264Reader.Listener() {
            @Override public void onReady() {
                runOnUiThread(() -> {
                    if (client == active && h264Reader != null) videoStatus.setText("Live screen (H.264)");
                });
            }
            @Override public void onSize(int width, int height) {
                runOnUiThread(() -> {
                    if (client != active || h264Reader == null) return;
                    videoWidth = width;
                    videoHeight = height;
                    updateFastVideoTransform();
                });
            }
            @Override public void onFailure() {
                runOnUiThread(() -> {
                    if (client != active || h264Reader == null) return;
                    h264Reader = null;
                    fastVideo.setVisibility(View.GONE);
                    videoWidth = videoHeight = 0;
                    startMjpegVideo(active);
                });
            }
        });
        h264Reader.start();
    }

    private void updateFastVideoTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || fastVideo.getWidth() <= 0 || fastVideo.getHeight() <= 0) return;
        clampVideoOffset();
        float scale = videoScale(fastVideo.getWidth(), fastVideo.getHeight(), videoWidth, videoHeight);
        Matrix transform = new Matrix();
        transform.setScale(videoWidth * scale / fastVideo.getWidth(),
                videoHeight * scale / fastVideo.getHeight(),
                fastVideo.getWidth() / 2f, fastVideo.getHeight() / 2f);
        transform.postTranslate(videoOffsetX, videoOffsetY);
        fastVideo.setTransform(transform);
    }

    private void startMjpegVideo(PikvmClient active) {
        fastVideo.setVisibility(View.GONE);
        AtomicReference<Bitmap> latestFrame = new AtomicReference<>();
        AtomicBoolean frameQueued = new AtomicBoolean();
        Runnable renderFrame = new Runnable() {
            @Override public void run() {
                Bitmap frame = latestFrame.getAndSet(null);
                if (frame != null && client == active) {
                    currentFrame = frame;
                    videoWidth = frame.getWidth();
                    videoHeight = frame.getHeight();
                    screen.invalidate();
                }
                frameQueued.set(false);
                if (latestFrame.get() != null && frameQueued.compareAndSet(false, true)) {
                    handler.post(this);
                }
            }
        };
        videoReader = new MjpegReader(active, new MjpegReader.Listener() {
            @Override public void onFrame(Bitmap frame) {
                latestFrame.set(frame);
                if (frameQueued.compareAndSet(false, true)) handler.post(renderFrame);
            }
            @Override public void onStatus(String status) {
                runOnUiThread(() -> { if (client == active) videoStatus.setText(status); });
            }
        });
        videoReader.start();
    }

    private void stopVideo() {
        if (h264Reader != null) {
            h264Reader.stop();
            h264Reader = null;
        }
        if (videoReader != null) {
            videoReader.stop();
            videoReader = null;
        }
        if (fastVideo != null) fastVideo.setVisibility(View.GONE);
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
            if (active) button.setBackground(rounded(KEY_ACCENT));
            else button.setBackgroundColor(CONSOLE_BG);
            button.setTextColor(active ? Color.rgb(16, 24, 40) : TEXT);
        }
    }

    private void sendKey(String code) {
        PikvmClient active = client;
        if (active == null) return;
        flushTextNow();
        List<String> held = consumeModifiers();
        if (terminalPanel != null) {
            terminalPanel.sendKey(code, held);
            return;
        }
        io.execute(() -> {
            try { active.sendKey(code, held); }
            catch (Exception error) { runOnUiThread(() -> showMessage(error.getMessage())); }
        });
    }

    private List<String> consumeModifiers() {
        List<String> held = new ArrayList<>(modifiers);
        modifiers.clear();
        updateModifierButtons();
        return held;
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
        handler.postDelayed(textFlush, 30);
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
        if (videoWidth <= 0 || videoHeight <= 0 || screen.getWidth() == 0 || screen.getHeight() == 0) return null;
        float scale = videoScale(screen.getWidth(), screen.getHeight(), videoWidth, videoHeight);
        float width = videoWidth * scale, height = videoHeight * scale;
        float left = (screen.getWidth() - width) / 2 + videoOffsetX;
        float top = (screen.getHeight() - height) / 2 + videoOffsetY;
        return new PointF(Math.max(0f, Math.min(1f, (x - left) / width)),
                Math.max(0f, Math.min(1f, (y - top) / height)));
    }

    private void queueMouseMove(float x, float y) {
        PointF point = screenPoint(x, y);
        if (point == null || client == null) return;
        pointerX = point.x;
        pointerY = point.y;
        enqueueMouseMove(point);
    }

    private void queueRelativeMouseMove(float dx, float dy) {
        if (client == null || videoWidth <= 0 || videoHeight <= 0) return;
        float scale = videoScale(screen.getWidth(), screen.getHeight(), videoWidth, videoHeight);
        float width = videoWidth * scale, height = videoHeight * scale;
        if (width <= 0 || height <= 0) return;
        pointerX = Math.max(0f, Math.min(1f, pointerX + dx * 1.35f / width));
        pointerY = Math.max(0f, Math.min(1f, pointerY + dy * 1.35f / height));
        enqueueMouseMove(new PointF(pointerX, pointerY));
    }

    private float videoScale(int viewWidth, int viewHeight, int frameWidth, int frameHeight) {
        float horizontal = (float) viewWidth / frameWidth;
        float vertical = (float) viewHeight / frameHeight;
        return (fillScreen ? Math.max(horizontal, vertical) : Math.min(horizontal, vertical))
                * screenScalePercent / 100f;
    }

    private void clampVideoOffset() {
        if (videoWidth <= 0 || videoHeight <= 0 || screen == null) return;
        float scale = videoScale(screen.getWidth(), screen.getHeight(), videoWidth, videoHeight);
        float maxX = Math.max(0f, (videoWidth * scale - screen.getWidth()) / 2f);
        float maxY = Math.max(0f, (videoHeight * scale - screen.getHeight()) / 2f);
        videoOffsetX = Math.max(-maxX, Math.min(maxX, videoOffsetX));
        videoOffsetY = Math.max(-maxY, Math.min(maxY, videoOffsetY));
    }

    private void enqueueMouseMove(PointF point) {
        pendingMove.set(point);
        if (moveQueued.compareAndSet(false, true)) mouseIo.execute(this::drainMouseMoves);
    }

    private void drainMouseMoves() {
        try {
            sendPendingMouseMoves(client);
        } catch (Exception error) {
            runOnUiThread(() -> showMessage(error.getMessage()));
        } finally {
            moveQueued.set(false);
            if (pendingMove.get() != null && moveQueued.compareAndSet(false, true)) {
                mouseIo.execute(this::drainMouseMoves);
            }
        }
    }

    private void sendPendingMouseMoves(PikvmClient active) throws Exception {
        while (true) {
            PointF point = pendingMove.getAndSet(null);
            if (point == null) return;
            if (active != null && client == active) {
                active.moveMouse(point.x, point.y);
            }
        }
    }

    private void mouseClick(String button, int count) {
        PikvmClient active = client;
        if (active == null) return;
        mouseIo.execute(() -> {
            try {
                sendPendingMouseMoves(active);
                active.mouseClick(button, count);
            }
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
            setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }

        @Override public boolean onCheckIsTextEditor() { return true; }

        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    | EditorInfo.IME_ACTION_NONE;
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
                    if (event.getAction() == KeyEvent.ACTION_MULTIPLE
                            && event.getCharacters() != null) {
                        sendRemoteText(event.getCharacters());
                        return true;
                    }
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

    private final class RemoteScreen extends View {
        private final Paint videoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private Runnable longPress;
        private Runnable pendingClick;
        private float startX, startY;
        private float lastX, lastY;
        private float lastTapX, lastTapY, scrollY;
        private float pinchStartDistance, pinchStartScale, pinchStartMidX, pinchStartMidY;
        private float pinchLastMidX, pinchLastMidY;
        private long lastTapAt;
        private boolean moved, multiTouch, scrolled, wheelScrolled, pinching, heldRightClick;

        RemoteScreen(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap frame = currentFrame;
            if (frame == null || getWidth() == 0 || getHeight() == 0) return;
            clampVideoOffset();
            float scale = videoScale(getWidth(), getHeight(), frame.getWidth(), frame.getHeight());
            float width = frame.getWidth() * scale, height = frame.getHeight() * scale;
            float left = (getWidth() - width) / 2 + videoOffsetX;
            float top = (getHeight() - height) / 2 + videoOffsetY;
            canvas.drawBitmap(frame, null, new RectF(left, top, left + width, top + height), videoPaint);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            clampVideoOffset();
            updateFastVideoTransform();
        }

        private float pointerDistance(MotionEvent event) {
            return (float) Math.hypot(event.getX(1) - event.getX(0),
                    event.getY(1) - event.getY(0));
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    lastX = startX;
                    lastY = startY;
                    moved = multiTouch = scrolled = wheelScrolled = pinching = heldRightClick = false;
                    resetKeyboardComposition();
                    if (!trackpadMode) queueMouseMove(startX, startY);
                    longPress = () -> {
                        if (!moved && !multiTouch && client != null) {
                            heldRightClick = true;
                            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                            mouseClick("right", 1);
                        }
                    };
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelRemoteLongPress();
                    multiTouch = true;
                    pinchLastMidX = (event.getX(0) + event.getX(1)) / 2f;
                    pinchLastMidY = (event.getY(0) + event.getY(1)) / 2f;
                    pinchStartMidX = pinchLastMidX;
                    pinchStartMidY = pinchLastMidY;
                    scrollY = pinchLastMidY;
                    pinchStartDistance = pointerDistance(event);
                    pinchStartScale = screenScalePercent;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (multiTouch && event.getPointerCount() >= 2) {
                        float midX = (event.getX(0) + event.getX(1)) / 2f;
                        float midY = (event.getY(0) + event.getY(1)) / 2f;
                        float distance = pointerDistance(event);
                        if (!wheelScrolled && !pinching && Math.abs(distance - pinchStartDistance) > dp(8))
                            pinching = true;
                        if (pinching && videoWidth > 0 && videoHeight > 0) {
                            PointF focus = screenPoint(pinchLastMidX, pinchLastMidY);
                            screenScalePercent = Math.max(50, Math.min(400,
                                    Math.round(pinchStartScale * distance / Math.max(1f, pinchStartDistance))));
                            float scale = videoScale(getWidth(), getHeight(), videoWidth, videoHeight);
                            if (focus != null) {
                                videoOffsetX = midX - (focus.x - 0.5f) * videoWidth * scale - getWidth() / 2f;
                                videoOffsetY = midY - (focus.y - 0.5f) * videoHeight * scale - getHeight() / 2f;
                            }
                            clampVideoOffset();
                            updateFastVideoTransform();
                            invalidate();
                        } else if (videoWidth > 0 && videoHeight > 0
                                && (videoWidth * videoScale(getWidth(), getHeight(), videoWidth, videoHeight) > getWidth() + 1
                                || videoHeight * videoScale(getWidth(), getHeight(), videoWidth, videoHeight) > getHeight() + 1)) {
                            videoOffsetX += midX - pinchLastMidX;
                            videoOffsetY += midY - pinchLastMidY;
                            clampVideoOffset();
                            updateFastVideoTransform();
                            invalidate();
                            if (Math.hypot(midX - pinchStartMidX, midY - pinchStartMidY) > slop)
                                scrolled = true;
                        } else {
                            int steps = (int) ((midY - scrollY) / dp(28));
                            if (steps != 0) {
                                mouseWheel(steps);
                                scrollY += steps * dp(28);
                                scrolled = true;
                                wheelScrolled = true;
                            }
                        }
                        pinchLastMidX = midX;
                        pinchLastMidY = midY;
                    } else if (!multiTouch) {
                        if (Math.hypot(event.getX() - startX, event.getY() - startY) > slop) {
                            moved = true;
                            cancelRemoteLongPress();
                        }
                        if (trackpadMode) queueRelativeMouseMove(event.getX() - lastX, event.getY() - lastY);
                        else queueMouseMove(event.getX(), event.getY());
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    return true;
                case MotionEvent.ACTION_UP:
                    cancelRemoteLongPress();
                    if (!multiTouch && moved) {
                        if (trackpadMode) queueRelativeMouseMove(event.getX() - lastX, event.getY() - lastY);
                        else queueMouseMove(event.getX(), event.getY());
                    }
                    if (pinching && client != null) {
                        getSharedPreferences("video_preferences", MODE_PRIVATE).edit()
                                .putInt(client.baseUrl + "|scale", screenScalePercent).apply();
                    }
                    if (multiTouch) {
                        if (!scrolled && !pinching) mouseClick("left", 2);
                    } else if (!moved && !heldRightClick) {
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
                    if (pinching && client != null) getSharedPreferences("video_preferences", MODE_PRIVATE)
                            .edit().putInt(client.baseUrl + "|scale", screenScalePercent).apply();
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
            pinching = heldRightClick = false;
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
        activeAddress = "";
        activeUser = "";
        stopVideo();
        handler.removeCallbacks(powerPoll);
        currentFrame = null;
        videoWidth = videoHeight = 0;
        screen.invalidate();
        modifiers.clear();
        updateModifierButtons();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(remoteInput.getWindowToken(), 0);
        setContentView(loginPage);
        resumePage = loginPage;
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
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        view.setSingleLine(true);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setTextSize(15);
        view.setHint(hint);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(rounded(SURFACE));
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
    private TextView deviceTypeLabel(String value, boolean dropdown) {
        TextView label = text(value + (dropdown ? "" : "  ▾"), 15, TEXT);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(14), 0, dp(14), 0);
        label.setMinHeight(dp(48));
        if (dropdown) label.setBackgroundColor(PANEL);
        return label;
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }
}
