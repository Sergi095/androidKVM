package org.androidkvm.app;

import java.util.Locale;

/** Validates the computer MAC address sent to the KVM's Wake-on-LAN API. */
final class WakeOnLan {
    private WakeOnLan() { }

    static String canonicalMac(String value) {
        String mac = value.trim();
        if (!mac.matches("(?i)[0-9a-f]{12}")
                && !mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
                && !mac.matches("(?i)([0-9a-f]{2}-){5}[0-9a-f]{2}")) {
            throw new IllegalArgumentException("Enter a 12-digit MAC address, e.g. AA:BB:CC:DD:EE:FF.");
        }
        String digits = mac.replace(":", "").replace("-", "").toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(17);
        for (int i = 0; i < digits.length(); i += 2) {
            if (i > 0) result.append(':');
            result.append(digits, i, i + 2);
        }
        return result.toString();
    }
}
