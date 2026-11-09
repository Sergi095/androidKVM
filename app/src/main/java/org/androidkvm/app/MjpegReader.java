package org.androidkvm.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import javax.net.ssl.HttpsURLConnection;

/** Reads a PiKVM-style Content-Length-framed MJPEG stream on its own thread. */
final class MjpegReader {
    interface Listener {
        void onFrame(Bitmap bitmap);
        void onStatus(String status);
    }

    private final PikvmClient client;
    private final Listener listener;
    private volatile boolean running;
    private volatile HttpsURLConnection connection;
    private Thread thread;

    MjpegReader(PikvmClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "androidKVM-video");
        thread.start();
    }

    void stop() {
        running = false;
        HttpsURLConnection active = connection;
        if (active != null) active.disconnect();
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                listener.onStatus("Connecting to video…");
                connection = client.openVideo();
                try (InputStream input = new BufferedInputStream(connection.getInputStream(), 65536)) {
                    listener.onStatus("Live screen");
                    readFrames(input);
                }
            } catch (IOException error) {
                if (running) listener.onStatus(error.getMessage());
            } finally {
                HttpsURLConnection active = connection;
                connection = null;
                if (active != null) active.disconnect();
            }
            if (running) {
                try { Thread.sleep(2000); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void readFrames(InputStream input) throws IOException {
        long lastFrame = 0;
        while (running) {
            String boundary = line(input);
            if (!boundary.startsWith("--")) {
                continue;
            }
            if (boundary.endsWith("--")) return;
            int length = -1;
            String header;
            while (!(header = line(input)).isEmpty()) {
                if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    try { length = Integer.parseInt(header.substring(15).trim()); }
                    catch (NumberFormatException e) { throw new IOException("Invalid video frame length", e); }
                }
            }
            if (length < 0 || length >= 4 * 1024 * 1024) throw new IOException("Invalid video frame size");
            if (length == 0) continue;
            byte[] jpeg = new byte[length];
            readFully(input, jpeg);
            long now = System.currentTimeMillis();
            if (now - lastFrame >= 100) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                if (bitmap != null) listener.onFrame(bitmap);
                lastFrame = now;
            }
        }
    }

    private static void readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) throw new EOFException("Video stream ended");
            offset += count;
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() < 2048) {
            int c = input.read();
            if (c < 0) throw new EOFException("Video stream ended");
            if (c == '\n') {
                byte[] bytes = output.toByteArray();
                int end = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
            }
            output.write(c);
        }
        throw new IOException("Video header is too long");
    }
}
