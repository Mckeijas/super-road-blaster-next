package com.srbport.roadblaster;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Plays the HD film locked to the game: the renderer sets the film time that matches the
 * picture the emulator is showing (from the MSU-1 audio position) and this decoder shows the
 * frame for that time, dropping or holding frames and seeking when the game jumps (chapter
 * select, checkpoints after a crash). Slow motion and fast forward follow automatically.
 *
 * Decoding happens on its own thread into a SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) that the
 * renderer samples.
 */
public final class HdVideo implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "SRBHdVideo";
    private static final double FRAME = 1.0 / 60.0;

    private final File file;
    private SurfaceTexture texture;
    private Surface surface;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean active;
    private volatile double target = -1;
    private volatile double rendered = -1;       // film time of the frame on the texture
    private volatile boolean frameReady;
    private volatile long lastSeekNanos;

    public HdVideo(File file) { this.file = file; }

    /** GL thread: (re)binds the decoder output to the given external texture. */
    public void attach(int oesTexture) {
        // a new GL context (app resumed): start over with a fresh texture and decoder,
        // the first frame comes back after a quick seek
        if (texture != null) release();
        rendered = -1;
        frameReady = false;
        texture = new SurfaceTexture(oesTexture);
        texture.setOnFrameAvailableListener(this);
        surface = new Surface(texture);
        running = true;
        thread = new Thread(this::decodeLoop, "srb-hd-video");
        thread.start();
    }

    public void setTarget(double t) { target = t; }
    public void setActive(boolean a) { active = a; }

    /** The texture shows (almost) the frame for the current target. */
    public boolean synced() {
        double r = rendered, t = target;
        return r >= 0 && t >= 0 && Math.abs(r - t) < 0.12;
    }

    /** GL thread: latches the newest decoded frame. */
    public void update(float[] texMatrix) {
        if (texture == null) return;
        if (frameReady) {
            frameReady = false;
            try { texture.updateTexImage(); } catch (Exception e) { Log.w(TAG, "updateTexImage", e); }
        }
        texture.getTransformMatrix(texMatrix);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) { frameReady = true; }

    public void release() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(1500); } catch (InterruptedException ignored) { }
        }
        if (surface != null) surface.release();
        if (texture != null) texture.release();
        surface = null;
        texture = null;
    }

    // ------------------------------------------------------------------ decoder thread
    private void decodeLoop() {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IllegalStateException("no video track");
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, surface, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int pending = -1;
            double pendingTime = -1, newest = -1;
            boolean needSeek = true;
            while (running) {
                if (!active || target < 0) {
                    sleep(15);
                    continue;
                }
                double t = target;
                // far from what we have: jump (keyframes are 1-5 s apart, the rest decodes fast)
                if (!needSeek && newest >= 0 && (t < newest - 0.25 || t > newest + 2.0)) needSeek = true;
                if (needSeek) {
                    if (pending >= 0) { codec.releaseOutputBuffer(pending, false); pending = -1; }
                    ex.seekTo((long) (t * 1e6), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    codec.flush();
                    inputDone = false;
                    newest = -1;
                    rendered = -1;
                    needSeek = false;
                    lastSeekNanos = System.nanoTime();
                }
                // feed the decoder
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(0);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                // take one decoded frame and show it when its time comes
                if (pending < 0) {
                    int out = codec.dequeueOutputBuffer(info, 2000);
                    if (out >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && info.size == 0) {
                            codec.releaseOutputBuffer(out, false);
                            sleep(10);
                            continue;
                        }
                        pending = out;
                        pendingTime = info.presentationTimeUs / 1e6;
                        newest = pendingTime;
                    }
                }
                if (pending >= 0) {
                    t = target;
                    if (pendingTime < t - 1.5 * FRAME) {          // late: skip it
                        codec.releaseOutputBuffer(pending, false);
                        pending = -1;
                    } else if (pendingTime <= t + 0.5 * FRAME) {  // its turn
                        codec.releaseOutputBuffer(pending, true);
                        rendered = pendingTime;
                        pending = -1;
                    } else {
                        sleep(2);                                  // early: wait for the game
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "decoder", e);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
            ex.release();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
