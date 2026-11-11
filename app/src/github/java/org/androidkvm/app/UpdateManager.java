package org.androidkvm.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Fetches and verifies signed APK releases from this project's GitHub repository. */
final class UpdateManager {
    private static final String REPOSITORY = "https://github.com/Sergi095/androidKVM";
    private static final String API = "https://api.github.com/repos/Sergi095/androidKVM/releases/latest";
    private static final long MAX_APK_BYTES = 50L * 1024 * 1024;
    private final Context context;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofMinutes(3)).build();

    UpdateManager(Context context) { this.context = context.getApplicationContext(); }

    static final class Release {
        final String tag, version, url, sha256;
        final long size;
        Release(String tag, String version, String url, String sha256, long size) {
            this.tag = tag;
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    interface Progress { void changed(int percent); }

    Release latest() throws Exception {
        Request request = new Request.Builder().url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "androidKVM")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                throw new IllegalStateException("GitHub release check failed (HTTP " + response.code() + ").");
            JSONObject json = new JSONObject(response.body().string());
            String tag = json.getString("tag_name");
            if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw new IllegalStateException("Latest release has an unexpected version tag.");
            String filename = "androidKVM-" + tag + ".apk";
            String expectedUrl = REPOSITORY + "/releases/download/" + tag + "/" + filename;
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!filename.equals(asset.optString("name"))) continue;
                String url = asset.getString("browser_download_url");
                String digest = asset.optString("digest");
                long size = asset.getLong("size");
                if (!expectedUrl.equals(url) || !digest.matches("sha256:[a-fA-F0-9]{64}")
                        || size <= 0 || size > MAX_APK_BYTES)
                    throw new IllegalStateException("Release APK metadata could not be verified.");
                return new Release(tag, tag.substring(1), url, digest.substring(7), size);
            }
            throw new IllegalStateException("Latest release has no androidKVM APK.");
        }
    }

    boolean newerThanInstalled(Release release) {
        return compareVersions(release.version, BuildConfig.VERSION_NAME) > 0;
    }

    private static int compareVersions(String a, String b) {
        String[] leftVersion = a.split("-", 2), rightVersion = b.split("-", 2);
        String[] left = leftVersion[0].split("\\."), right = rightVersion[0].split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long x = i < left.length ? Long.parseLong(left[i]) : 0;
            long y = i < right.length ? Long.parseLong(right[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return Boolean.compare(rightVersion.length > 1, leftVersion.length > 1);
    }

    File download(Release release, Progress progress) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Could not create update cache.");
        File temp = new File(directory, "androidKVM-update.part.apk");
        File apk = new File(directory, "androidKVM-update.apk");
        if (temp.exists() && !temp.delete()) throw new IllegalStateException("Could not replace old download.");
        Request request = new Request.Builder().url(release.url)
                .header("User-Agent", "androidKVM").build();
        try {
            try (Response response = http.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null || !"https".equals(response.request().url().scheme()))
                    throw new IllegalStateException("APK download failed (HTTP " + response.code() + ").");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                int lastPercent = -1;
                try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_APK_BYTES || total > release.size)
                            throw new IllegalStateException("APK is larger than its release metadata.");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        int percent = (int) (100 * total / release.size);
                        if (percent >= lastPercent + 5) {
                            lastPercent = percent;
                            progress.changed(percent);
                        }
                    }
                    output.getFD().sync();
                }
                if (total != release.size || !release.sha256.equalsIgnoreCase(hex(digest.digest())))
                    throw new IllegalStateException("APK checksum does not match the GitHub release.");
            }
            verifyPackage(temp, release);
            if (apk.exists() && !apk.delete()) throw new IllegalStateException("Could not replace old APK.");
            if (!temp.renameTo(apk)) throw new IllegalStateException("Could not save downloaded APK.");
            return apk;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private void verifyPackage(File apk, Release release) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flag = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES
                : PackageManager.GET_SIGNATURES;
        PackageInfo incoming = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flag);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flag);
        if (incoming == null || !context.getPackageName().equals(incoming.packageName)
                || !release.version.equals(incoming.versionName)
                || incoming.getLongVersionCode() <= installed.getLongVersionCode())
            throw new IllegalStateException("Downloaded APK is not a newer androidKVM build.");
        Signature[] newSigners = signers(incoming), currentSigners = signers(installed);
        if (newSigners == null || currentSigners == null || newSigners.length != currentSigners.length)
            throw new IllegalStateException("Downloaded APK signing info is missing or incomplete (" +
                    (newSigners == null ? "none" : newSigners.length) + " / " +
                    (currentSigners == null ? "none" : currentSigners.length) + ").");
        for (int i = 0; i < newSigners.length; i++) {
            if (!Arrays.equals(newSigners[i].toByteArray(), currentSigners[i].toByteArray()))
                throw new IllegalStateException("Downloaded APK has an unexpected signing key.");
        }
    }

    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures != null && signatures.length > 0) return signatures;
        }
        return info.signatures;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}
