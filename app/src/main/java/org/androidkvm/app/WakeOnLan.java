package org.androidkvm.app;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

/** Sends a Wake-on-LAN magic packet to a saved computer's network. */
final class WakeOnLan {
    private WakeOnLan() { }

    interface SocketBinder { void bind(DatagramSocket socket) throws Exception; }

    static final class SendResult {
        final boolean localSent, vpnSent;
        final String localError, vpnError;

        SendResult(boolean localSent, boolean vpnSent, String localError, String vpnError) {
            this.localSent = localSent;
            this.vpnSent = vpnSent;
            this.localError = localError;
            this.vpnError = vpnError;
        }

        String message() {
            if (localSent && vpnSent) return "WOL packets sent to local and VPN destinations.";
            if (localSent && vpnError.isEmpty()) return "WOL packet sent to local destination.";
            if (localSent) return "Local WOL packet sent; VPN send failed: " + vpnError;
            if (vpnSent) return "VPN WOL packet sent; local send failed: " + localError;
            return "WOL failed. Local: " + localError
                    + (vpnError.isEmpty() ? "" : "; VPN: " + vpnError);
        }
    }

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

    static String destination(String value) {
        String destination = value.trim();
        if (!destination.matches("[A-Za-z0-9._-]{1,253}"))
            throw new IllegalArgumentException("Enter an IPv4 broadcast address or hostname.");
        return destination;
    }

    static int port(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("UDP port must be between 1 and 65535.");
    }

    static byte[] magicPacket(String mac) {
        String digits = canonicalMac(mac).replace(":", "");
        byte[] packet = new byte[102];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 6; j++) {
                packet[6 + i * 6 + j] = (byte) Integer.parseInt(digits.substring(j * 2, j * 2 + 2), 16);
            }
        }
        return packet;
    }

    static void send(String mac, String destination, int port) throws Exception {
        send(mac, destination, port, null);
    }

    private static void send(String mac, String destination, int port, SocketBinder binder) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid UDP port.");
        InetAddress address = InetAddress.getByName(destination(destination));
        if (!(address instanceof Inet4Address))
            throw new IllegalArgumentException("Wake-on-LAN needs an IPv4 destination.");
        byte[] bytes = magicPacket(mac);
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            if (binder != null) binder.bind(socket);
            socket.send(packet);
        }
    }

    static SendResult sendBoth(String mac, String localDestination,
                               String vpnDestination, int port) {
        return sendBoth(mac, localDestination, vpnDestination, port, null, null);
    }

    static SendResult sendBoth(String mac, String localDestination, String vpnDestination,
                               int port, SocketBinder localBinder, SocketBinder vpnBinder) {
        boolean localSent = false, vpnSent = false;
        String localError = "", vpnError = "";
        try {
            send(mac, localDestination, port, localBinder);
            localSent = true;
        } catch (Exception error) {
            localError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        if (!vpnDestination.isEmpty() || vpnBinder != null) {
            try {
                send(mac, vpnDestination.isEmpty() ? "255.255.255.255" : vpnDestination,
                        port, vpnBinder);
                vpnSent = true;
            } catch (Exception error) {
                vpnError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
        }
        return new SendResult(localSent, vpnSent, localError, vpnError);
    }
}
