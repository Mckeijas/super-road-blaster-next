package com.srbport.roadblaster;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs the emulator. Pacing comes from the audio device: AudioTrack.write() blocks
 * until there is room, so the game always runs in sync with the sound (and slow
 * motion / fast forward just change how much audio each frame produces).
 */
public final class EmuThread extends Thread {
    public interface Listener {
        void onFrame();          // a new video frame is ready
        void onRumble(int strength);
    }

    private static final int RATE = 48000;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile boolean paused = false;
    private AudioTrack track;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    /** Runs r on the emulation thread between two frames (states, resets...). */
    public void post(Runnable r) { tasks.add(r); }

    private void drainTasks() {
        Runnable r;
        while ((r = tasks.poll()) != null) {
            try { r.run(); } catch (Throwable t) { android.util.Log.e("SRBEmu", "task", t); }
        }
    }

    public EmuThread(Listener l) {
        super("srb-emu");
        listener = l;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf * 2, RATE * 4 * 70 / 1000); // ~70 ms
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        track.play();
        short[] audio = new short[RATE]; // 0.5 s stereo, far more than one frame
        boolean trackPaused = false;
        while (running) {
            drainTasks();
            if (paused) {
                if (!trackPaused) { track.pause(); track.flush(); trackPaused = true; }
                sleepQuietly(10);
                continue;
            }
            if (trackPaused) { track.play(); trackPaused = false; }
            int frames = NativeBridge.nativeRunFrame(audio);
            listener.onFrame();
            int r = NativeBridge.nativeRumble();
            if (r > 0) listener.onRumble(r);
            if (frames > 0) {
                track.write(audio, 0, frames * 2, AudioTrack.WRITE_BLOCKING);
            } else {
                sleepQuietly(4); // slow motion warm-up: time stretcher buffering
            }
        }
        drainTasks();
        track.stop();
        track.release();
    }

    public void setPausedState(boolean p) {
        paused = p;
        NativeBridge.nativeSetPaused(p);
    }

    public boolean isPausedState() { return paused; }

    public void shutdown() {
        running = false;
        interrupt();
        try { join(2000); } catch (InterruptedException ignored) { }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
