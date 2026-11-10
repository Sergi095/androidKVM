package org.androidkvm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.TextureView;

import androidx.core.content.FileProvider;

import java.io.File;
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
    private final StringBuilder pendingText = new StringBuilder();
    private final Runnable textFlush = this::flushTextNow;
    private final AtomicReference<PointF> pendingMove = new AtomicReference<>();
    private final AtomicBoolean moveQueued = new AtomicBoolean();
    private final LinkedHashSet<String> modifiers = new LinkedHashSet<>();
    private final List<Button> modifierButtons = new ArrayList<>();
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
    private String trustedAddress = "", trustedFingerprint = "";
    private String activeAddress = "", activeUser = "";
    private CheckBox rememberInput;
    private ProfileStore profileStore;
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
        updateManager = new UpdateManager(this);
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
        updatesIo.shutdownNow();
        wolIo.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        loginPage = new ScrollView(this);
        loginPage.setFillViewport(true);
        loginPage.setBackgroundColor(BG);
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
        brand.addView(brandText);
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
            loginPanel.setVisibility(View.VISIBLE);
            addKvmButton.setVisibility(View.GONE);
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
        closeForm.setContentDescription("Close connection form");
        closeForm.setOnClickListener(view -> {
            if (!profileStore.list().isEmpty()) {
                loginPanel.setVisibility(View.GONE);
                addKvmButton.setVisibility(View.VISIBLE);
            }
        });
        formHeader.addView(closeForm, new LinearLayout.LayoutParams(dp(38), dp(38)));
        closeForm.setBackground(rounded(PANEL));
        closeForm.setTextColor(MUTED);
        loginPanel.addView(formHeader);
        loginPanel.addView(text("Use the login from your KVM web page.", 13, MUTED));
        addressInput = field("PiKVM address, e.g. pikvm.local", false);
        userInput = field("Username", false);
        passwordInput = field("Password", true);
        trustedAddress = getPreferences(0).getString("address", "");
        trustedFingerprint = getPreferences(0).getString("pin", "");
        addressInput.setText(trustedAddress);
        userInput.setText(getPreferences(0).getString("user", ""));
        for (EditText input : new EditText[]{addressInput, userInput, passwordInput}) {
            loginPanel.addView(input, spaced());
        }
        rememberInput = new CheckBox(this);
        rememberInput.setText("Save this KVM and password on this phone");
        rememberInput.setTextColor(TEXT);
        rememberInput.setChecked(true);
        loginPanel.addView(rememberInput, spaced());
        loginPanel.addView(text("Passwords and trusted certificates stay on this phone. For 2FA, use a fresh code.", 12, MUTED), spaced());
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
            view.setPadding(0, consoleTopInset, 0, consoleBottomInset);
            view.post(this::updateConsoleKeyboardInset);
            return insets;
        });
        consolePage.getViewTreeObserver().addOnGlobalLayoutListener(this::updateConsoleKeyboardInset);
        LinearLayout toolbar = row();
        toolbar.setPadding(dp(6), 0, dp(6), 0);
        toolbar.setBackgroundColor(CONSOLE_BG);
        Button disconnect = compactButton("‹");
        disconnect.setTextSize(29);
        disconnect.setContentDescription("Disconnect and choose another KVM");
        disconnect.setOnClickListener(view -> logout());
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
        screen.setContentDescription("Remote screen. Touch to move pointer; tap to click.");
        videoArea.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        consolePage.addView(videoArea, new LinearLayout.LayoutParams(-1, 0, 1));

        videoStatus = text("Connecting to video…", 11, MUTED);
        videoStatus.setPadding(dp(8), 0, dp(8), 0);
        consolePage.addView(videoStatus, new LinearLayout.LayoutParams(-1, dp(18)));

        extraKeys = column();
        extraKeys.setBackgroundColor(CONSOLE_BG);
        extraKeys.addView(compactKeyRow(new String[][]{
                {"ESC", "Escape"}, {"/", "Slash"}, {"−", "Minus"},
                {"HOME", "Home"}, {"↑", "ArrowUp"}, {"END", "End"}, {"PGUP", "PageUp"}
        }));
        extraKeys.addView(compactKeyRow(new String[][]{
                {"TAB", "Tab"}, {"CTRL", "ControlLeft"}, {"ALT", "AltLeft"},
                {"←", "ArrowLeft"}, {"↓", "ArrowDown"}, {"→", "ArrowRight"}, {"PGDN", "PageDown"}
        }));
        String[][] functionKeys = new String[13][2];
        for (int n = 1; n <= 12; n++) {
            functionKeys[n - 1][0] = "F" + n;
            functionKeys[n - 1][1] = "F" + n;
        }
        functionKeys[12][0] = "DEL";
        functionKeys[12][1] = "Delete";
        extraKeys.addView(keyRow(functionKeys));
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

    private void updateConsoleKeyboardInset() {
        if (consolePage == null || !consolePage.isAttachedToWindow() || consolePage.getHeight() == 0) return;
        Rect visible = new Rect();
        consolePage.getWindowVisibleDisplayFrame(visible);
        WindowInsets insets = consolePage.getRootWindowInsets();
        boolean keyboardShown = (Build.VERSION.SDK_INT >= 30 && insets != null
                && insets.isVisible(WindowInsets.Type.ime()))
                || consolePage.getRootView().getHeight() - visible.bottom > dp(140);
        if (extraKeys != null) extraKeys.setVisibility(keyboardShown ? View.VISIBLE : View.GONE);
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
        WindowInsets insets = consolePage.getRootWindowInsets();
        boolean shown = (Build.VERSION.SDK_INT >= 30 && insets != null
                && insets.isVisible(WindowInsets.Type.ime()))
                || consolePage.getRootView().getHeight() - visible.bottom > dp(140);
        if (shown) {
            keyboard.hideSoftInputFromWindow(remoteInput.getWindowToken(), 0);
        } else {
            remoteInput.requestFocus();
            keyboard.restartInput(remoteInput);
            remoteInput.post(() -> keyboard.showSoftInput(remoteInput, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private void showMouseModeDialog() {
        new AlertDialog.Builder(this)
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
                .show();
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
            detail.addView(text(profile.user + (profile.wolMac.isEmpty()
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
            remove.setOnClickListener(view -> new AlertDialog.Builder(this)
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
                    }).show());
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
        form.addView(text("Enter the computer's wired network MAC, not the KVM's. "
                + "NanoKVM sends WOL directly; PiKVM needs its WOL GPIO output configured.", 13, MUTED));
        EditText mac = field("Computer MAC, e.g. AA:BB:CC:DD:EE:FF", false);
        mac.setText(profile.wolMac);
        form.addView(mac, spaced());
        EditText gpioChannel = field("PiKVM WOL GPIO channel (optional)", false);
        gpioChannel.setText(profile.wolGpioChannel);
        form.addView(gpioChannel, spaced());
        form.addView(text("Leave the channel blank to detect a WOL-named PiKVM output. "
                + "If PiKVM has several WOL outputs, enter the channel for this computer.",
                12, MUTED), spaced());
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
                        String channel = gpioChannel.getText().toString().trim();
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
        dialog.show();
    }

    private ProfileStore.Profile activeWakeProfile() {
        for (ProfileStore.Profile profile : profileStore.list()) {
            if (profile.address.equals(activeAddress) && profile.user.equals(activeUser)
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
        addressInput.setText(profile.address);
        userInput.setText(profile.user);
        trustedAddress = profile.address;
        trustedFingerprint = profile.pin;
        try {
            passwordInput.setText(profileStore.password(profile));
            rememberInput.setChecked(true);
            login();
        } catch (Exception error) {
            passwordInput.setText("");
            showMessage("Saved password is unavailable. Enter it again to reconnect.");
        }
    }

    private void login() {
        String address = addressInput.getText().toString().trim();
        String user = userInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String pin = address.equals(trustedAddress) ? trustedFingerprint : "";
        boolean remember = rememberInput.isChecked();
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
                next.prepareNativeVideo(user, password);
                String saveError = null;
                if (remember) {
                    try { profileStore.save(address, user, pin, password); }
                    catch (Exception error) { saveError = "Connected, but could not save this KVM."; }
                }
                String finalSaveError = saveError;
                runOnUiThread(() -> {
                    client = next;
                    activeAddress = address;
                    activeUser = user;
                    consoleTitle.setText(address);
                    if (remember) {
                        getPreferences(0).edit().putString("address", address).putString("user", user)
                                .putString("pin", pin).apply();
                    } else {
                        getPreferences(0).edit().clear().apply();
                    }
                    passwordInput.setText("");
                    renderSavedProfiles();
                    setContentView(consolePage);
                    loginButton.setEnabled(true);
                    showMessage(finalSaveError == null ? "Connected" : finalSaveError);
                    if (finalSaveError != null) Toast.makeText(this, finalSaveError, Toast.LENGTH_LONG).show();
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
                    try {
                        String fingerprint = PikvmClient.certificateFingerprint(address);
                        runOnUiThread(() -> showCertificateDialog(address, fingerprint, !pin.isEmpty()));
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

    private void showCertificateDialog(String address, String fingerprint, boolean changed) {
        loginButton.setEnabled(true);
        showMessage("Trust confirmation required");
        new AlertDialog.Builder(this)
                .setTitle(changed ? "KVM certificate changed" : "Trust this KVM?")
                .setMessage(changed
                        ? "The certificate for " + address + " changed since you last trusted it. Continue only if you expect this change."
                        : "Android does not recognize the certificate for " + address
                                + ". Trust this KVM to connect? Your choice is remembered when you save the KVM.")
                .setNegativeButton("Cancel", (dialog, which) -> showMessage("Connection cancelled"))
                .setPositiveButton("Trust and connect", (dialog, which) -> {
                    trustedAddress = address;
                    trustedFingerprint = fingerprint;
                    login();
                })
                .show();
    }

    private void startVideo(PikvmClient active) {
        stopVideo();
        active.ensureEvents();
        currentFrame = null;
        videoWidth = videoHeight = 0;
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
        float scale = Math.min((float) fastVideo.getWidth() / videoWidth,
                (float) fastVideo.getHeight() / videoHeight);
        Matrix transform = new Matrix();
        transform.setScale(videoWidth * scale / fastVideo.getWidth(),
                videoHeight * scale / fastVideo.getHeight(),
                fastVideo.getWidth() / 2f, fastVideo.getHeight() / 2f);
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
        float scale = Math.min((float) screen.getWidth() / videoWidth,
                (float) screen.getHeight() / videoHeight);
        float width = videoWidth * scale, height = videoHeight * scale;
        float left = (screen.getWidth() - width) / 2, top = (screen.getHeight() - height) / 2;
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
        float scale = Math.min((float) screen.getWidth() / videoWidth,
                (float) screen.getHeight() / videoHeight);
        float width = videoWidth * scale, height = videoHeight * scale;
        if (width <= 0 || height <= 0) return;
        pointerX = Math.max(0f, Math.min(1f, pointerX + dx * 1.35f / width));
        pointerY = Math.max(0f, Math.min(1f, pointerY + dy * 1.35f / height));
        enqueueMouseMove(new PointF(pointerX, pointerY));
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

    private void mouseButton(String button, boolean down) {
        PikvmClient active = client;
        if (active == null) return;
        mouseIo.execute(() -> {
            try {
                sendPendingMouseMoves(active);
                active.mouseButton(button, down);
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

    private final class RemoteScreen extends View {
        private final Paint videoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private Runnable longPress;
        private Runnable pendingClick;
        private float startX, startY;
        private float lastX, lastY;
        private float lastTapX, lastTapY, scrollY;
        private long lastTapAt;
        private boolean moved, multiTouch, scrolled, dragging;

        RemoteScreen(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap frame = currentFrame;
            if (frame == null || getWidth() == 0 || getHeight() == 0) return;
            float scale = Math.min((float) getWidth() / frame.getWidth(),
                    (float) getHeight() / frame.getHeight());
            float width = frame.getWidth() * scale, height = frame.getHeight() * scale;
            float left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
            canvas.drawBitmap(frame, null, new RectF(left, top, left + width, top + height), videoPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    lastX = startX;
                    lastY = startY;
                    moved = multiTouch = scrolled = dragging = false;
                    resetKeyboardComposition();
                    if (!trackpadMode) queueMouseMove(startX, startY);
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
                    if (dragging) {
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
    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }
}
