package org.androidkvm.app;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Native NanoKVM Direct H.264 stream, decoded by Android's video codec. */
final class H264Reader {
    private static final String TAG = "androidKVM-video";
    interface Listener {
        void onReady();
        void onSize(int width, int height);
        void onFailure();
    }

    private static final class Frame {
        final byte[] bytes;
        final boolean key;
        final long timestamp;

        Frame(byte[] bytes) {
            this.bytes = bytes;
            key = bytes[0] == 1;
            timestamp = ByteBuffer.wrap(bytes, 1, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }
    }

    private final PikvmClient client;
    private final Surface surface;
    private final Listener listener;
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(4);
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean running;
    private volatile WebSocket socket;
    private Thread worker;
    private boolean ready;

    H264Reader(PikvmClient client, Surface surface, Listener listener) {
        this.client = client;
        this.surface = surface;
        this.listener = listener;
    }

    void start() {
        running = true;
        worker = new Thread(this::decodeLoop, "androidKVM-h264");
        worker.start();
        socket = client.openNativeVideo(new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, ByteString payload) {
                if (!running || payload.size() < 10) return;
                if (!frames.offer(new Frame(payload.toByteArray()))) fail();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                Log.w(TAG, "Native H.264 socket failed", error);
                fail();
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                fail();
            }
        });
    }

    void stop() {
        running = false;
        WebSocket active = socket;
        if (active != null) active.close(1000, "Video stopped");
        if (worker != null) worker.interrupt();
    }

    private void fail() {
        if (!running || !failed.compareAndSet(false, true)) return;
        stop();
        listener.onFailure();
    }

    private void decodeLoop() {
        MediaCodec codec = null;
        long started = SystemClock.elapsedRealtime();
        try {
            while (running) {
                Frame frame = frames.poll(10, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    if (codec == null) {
                        if (!frame.key) {
                            requestResync();
                            continue;
                        }
                        codec = createCodec(frame.bytes);
                    }
                    long waitStarted = SystemClock.elapsedRealtime();
                    int index = -1;
                    while (running && (index = codec.dequeueInputBuffer(10_000)) < 0) {
                        drainOutput(codec);
                        if (SystemClock.elapsedRealtime() - waitStarted > 1000) {
                            throw new IllegalStateException("H.264 decoder input stalled");
                        }
                    }
                    if (!running) break;
                    ByteBuffer input = codec.getInputBuffer(index);
                    if (input == null || input.capacity() < frame.bytes.length - 9) {
                        throw new IllegalStateException("H.264 frame exceeds decoder input");
                    }
                    input.clear();
                    input.put(frame.bytes, 9, frame.bytes.length - 9);
                    codec.queueInputBuffer(index, 0, frame.bytes.length - 9, frame.timestamp,
                            frame.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    acknowledge(frame.timestamp);
                }
                if (codec != null) drainOutput(codec);
                if (!ready && SystemClock.elapsedRealtime() - started > 8000) {
                    throw new IllegalStateException("H.264 decoder did not produce a frame");
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            Log.w(TAG, "Native H.264 decoder failed", error);
            fail();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            surface.release();
        }
    }

    private void drainOutput(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int output;
        while ((output = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                listener.onSize(format.getInteger(MediaFormat.KEY_WIDTH),
                        format.getInteger(MediaFormat.KEY_HEIGHT));
            } else if (output >= 0) {
                codec.releaseOutputBuffer(output, true);
                if (!ready) {
                    ready = true;
                    listener.onReady();
                }
            }
        }
    }

    private MediaCodec createCodec(byte[] keyFrame) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
        byte[][] csd = findCodecData(keyFrame);
        if (csd[0] != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd[0]));
        if (csd[1] != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd[1]));
        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            codec.configure(format, surface, null, 0);
            codec.start();
            return codec;
        } catch (Exception error) {
            codec.release();
            throw error;
        }
    }

    private static byte[][] findCodecData(byte[] frame) {
        byte[][] result = new byte[2][];
        for (int start = 9; start + 5 < frame.length; start++) {
            if (frame[start] != 0 || frame[start + 1] != 0) continue;
            int prefix = frame[start + 2] == 1 ? 3
                    : (frame[start + 2] == 0 && frame[start + 3] == 1 ? 4 : 0);
            if (prefix == 0) continue;
            int type = frame[start + prefix] & 31;
            if (type != 7 && type != 8) continue;
            int end = start + prefix + 1;
            while (end + 3 < frame.length) {
                if (frame[end] == 0 && frame[end + 1] == 0
                        && (frame[end + 2] == 1 || (frame[end + 2] == 0 && frame[end + 3] == 1))) break;
                end++;
            }
            if (end + 3 >= frame.length) end = frame.length;
            result[type == 7 ? 0 : 1] = java.util.Arrays.copyOfRange(frame, start, end);
            start = end - 1;
        }
        return result;
    }

    private void acknowledge(long timestamp) {
        WebSocket active = socket;
        if (active == null || !running) return;
        byte[] message = new byte[9];
        message[0] = 2;
        ByteBuffer.wrap(message, 1, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(timestamp);
        active.send(ByteString.of(message));
    }

    private void requestResync() {
        WebSocket active = socket;
        if (active != null) active.send(ByteString.of(new byte[]{3}));
    }
}
