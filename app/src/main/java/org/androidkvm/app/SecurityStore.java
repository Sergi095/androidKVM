package org.androidkvm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** App lock preferences. The PIN is never stored; only a salted, stretched hash is. */
final class SecurityStore {
    private static final int ITERATIONS = 210_000;
    private static final int HASH_BITS = 256;
    private final SharedPreferences preferences;

    SecurityStore(Context context) {
        preferences = context.getSharedPreferences("app_security", Context.MODE_PRIVATE);
    }

    boolean hasPin() { return preferences.contains("pin_hash") && preferences.contains("pin_salt"); }
    boolean hasBiometrics() { return preferences.getBoolean("biometrics", false); }
    boolean enabled() { return hasPin() || hasBiometrics(); }

    void setPin(String value) throws Exception {
        if (!value.matches("[0-9]{6,12}")) {
            throw new IllegalArgumentException("Use a PIN of 6 to 12 digits.");
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(value, salt);
        if (!preferences.edit()
                .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt("failed_pins", 0).putLong("retry_after", 0)
                .commit()) throw new IllegalStateException("Could not save the PIN.");
        Arrays.fill(hash, (byte) 0);
    }

    boolean verifyPin(String value) throws Exception {
        if (!hasPin() || value == null || value.length() > 32) return false;
        byte[] salt = Base64.decode(preferences.getString("pin_salt", ""), Base64.DEFAULT);
        byte[] expected = Base64.decode(preferences.getString("pin_hash", ""), Base64.DEFAULT);
        if (salt.length != 16 || expected.length != HASH_BITS / 8) return false;
        byte[] actual = derive(value, salt);
        boolean matches = MessageDigest.isEqual(actual, expected);
        Arrays.fill(actual, (byte) 0);
        return matches;
    }

    void removePin() {
        if (!preferences.edit().remove("pin_salt").remove("pin_hash")
                .remove("failed_pins").remove("retry_after").commit()) {
            throw new IllegalStateException("Could not remove the PIN.");
        }
    }

    void setBiometrics(boolean enabled) {
        if (!preferences.edit().putBoolean("biometrics", enabled).commit()) {
            throw new IllegalStateException("Could not update biometric settings.");
        }
    }

    long retrySeconds() {
        long remaining = preferences.getLong("retry_after", 0) - System.currentTimeMillis();
        return Math.max(0, (remaining + 999) / 1000);
    }

    void recordFailure() {
        int failures = preferences.getInt("failed_pins", 0) + 1;
        long delay = failures >= 10 ? 300_000 : failures >= 5 ? 30_000 : 0;
        preferences.edit().putInt("failed_pins", failures)
                .putLong("retry_after", System.currentTimeMillis() + delay).apply();
    }

    void clearFailures() {
        preferences.edit().putInt("failed_pins", 0).putLong("retry_after", 0).apply();
    }

    private static byte[] derive(String value, byte[] salt) throws Exception {
        char[] chars = value.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
