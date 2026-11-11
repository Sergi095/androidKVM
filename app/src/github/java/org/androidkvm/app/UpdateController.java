package org.androidkvm.app;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** GitHub release updates for APKs signed by the project's release key. */
final class UpdateController {
    private static final int INSTALL_PERMISSION_REQUEST = 1701;

    private final MainActivity activity;
    private final TextView status;
    private final Button checkButton, installButton;
    private final UpdateManager manager;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private UpdateManager.Release latestRelease;
    private File pendingInstall;
    private boolean busy;

    UpdateController(MainActivity activity, TextView status, Button checkButton, Button installButton) {
        this.activity = activity;
        this.status = status;
        this.checkButton = checkButton;
        this.installButton = installButton;
        manager = new UpdateManager(activity);
    }

    boolean needsCheck() { return latestRelease == null && !busy; }

    void checkForUpdates() {
        if (busy) return;
        busy = true;
        latestRelease = null;
        pendingInstall = null;
        checkButton.setEnabled(false);
        installButton.setVisibility(View.GONE);
        status.setText("Checking GitHub Releases…");
        io.execute(() -> {
            try {
                UpdateManager.Release release = manager.latest();
                boolean newer = manager.newerThanInstalled(release);
                activity.runOnUiThread(() -> {
                    busy = false;
                    checkButton.setEnabled(true);
                    latestRelease = release;
                    status.setText(newer
                            ? "Version " + release.version + " is available (" + (release.size / 1024) + " KB)."
                            : "You're up to date. Latest release: " + release.version + ".");
                    installButton.setVisibility(newer ? View.VISIBLE : View.GONE);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    busy = false;
                    checkButton.setEnabled(true);
                    status.setText("Could not check for updates: " + error.getMessage());
                });
            }
        });
    }

    void downloadOrInstallUpdate() {
        if (pendingInstall != null && pendingInstall.isFile()) {
            installDownloadedUpdate();
            return;
        }
        UpdateManager.Release release = latestRelease;
        if (release == null || busy || !manager.newerThanInstalled(release)) return;
        busy = true;
        checkButton.setEnabled(false);
        installButton.setEnabled(false);
        status.setText("Downloading from GitHub…");
        io.execute(() -> {
            try {
                File apk = manager.download(release, percent -> activity.runOnUiThread(() ->
                        status.setText("Downloading from GitHub… " + percent + "%")));
                activity.runOnUiThread(() -> {
                    busy = false;
                    checkButton.setEnabled(true);
                    installButton.setEnabled(true);
                    installButton.setText("Install downloaded APK");
                    pendingInstall = apk;
                    status.setText("Version " + release.version + " downloaded and verified.");
                    installDownloadedUpdate();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    busy = false;
                    checkButton.setEnabled(true);
                    installButton.setEnabled(true);
                    status.setText("Update failed: " + error.getMessage());
                });
            }
        });
    }

    private void installDownloadedUpdate() {
        if (pendingInstall == null || !pendingInstall.isFile()) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow androidKVM to install apps, then return to finish the update.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try { activity.startActivityForResult(settings, INSTALL_PERMISSION_REQUEST); }
            catch (Exception error) { status.setText("Open Android settings and allow installs from androidKVM."); }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".updates", pendingInstall);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.setClipData(ClipData.newRawUri("androidKVM update", uri));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
            status.setText("Confirm the update in Android's installer.");
        } catch (Exception error) {
            status.setText("Could not open Android's installer: " + error.getMessage());
        }
    }

    void onActivityResult(int requestCode) {
        if (requestCode == INSTALL_PERMISSION_REQUEST
                && activity.getPackageManager().canRequestPackageInstalls())
            installDownloadedUpdate();
    }

    void close() { io.shutdownNow(); }
}
