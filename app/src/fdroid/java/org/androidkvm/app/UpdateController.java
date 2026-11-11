package org.androidkvm.app;

import android.widget.Button;
import android.widget.TextView;

/** F-Droid installs updates itself; this variant has no APK downloader. */
final class UpdateController {
    UpdateController(MainActivity activity, TextView status, Button checkButton, Button installButton) { }
    boolean needsCheck() { return false; }
    void checkForUpdates() { }
    void downloadOrInstallUpdate() { }
    void onActivityResult(int requestCode) { }
    void close() { }
}
