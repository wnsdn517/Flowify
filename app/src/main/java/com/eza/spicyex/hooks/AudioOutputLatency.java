package com.eza.spicyex.hooks;

import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.SparseArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.eza.spicyex.xposed.XpLog;

/**
 * How far the sound being heard trails the position Spotify reports.
 *
 * <p>Spotify's own player renders through a Java AudioTrack owned by
 * {@code com.spotify.playbacknative.AudioDriver} (a JNI class, so its name and members survive
 * obfuscation). The native core derives the playback position from that track's playback head -
 * the frames the mixer has taken - which runs ahead of what leaves the speaker by the output
 * path's own latency: the mixer and HAL buffers (around 100 ms on a deep-buffer output) and, on
 * Bluetooth, the codec and the headset (another 150-300 ms). AudioTrack#getTimestamp reports
 * the frame actually being presented, so the gap between the two is that latency, measured live
 * for whatever output is in use. Nothing is hooked: the driver is read from its static session map.
 */
final class AudioOutputLatency {
    private static final String DRIVER_CLASS = "com.spotify.playbacknative.AudioDriver";
    /** Re-measured at most this often; the latency only changes with the output route. */
    private static final long SAMPLE_INTERVAL_MS = 500L;
    private static final long MAX_LATENCY_MS = 600L;

    private final ClassLoader classLoader;
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private boolean resolved;
    private Field sessionMapField;
    private Field currentSessionField;
    private Method getAudioTrack;
    private long sampledAtMs = Long.MIN_VALUE / 2;
    private float latencyMs = -1f;
    private boolean logged;

    AudioOutputLatency(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** The smoothed output latency in ms, or 0 while no local Spotify track is playing. */
    synchronized long latencyMs() {
        long now = SystemClock.uptimeMillis();
        if (now - sampledAtMs >= SAMPLE_INTERVAL_MS) {
            sampledAtMs = now;
            sample();
        }
        return latencyMs <= 0f ? 0L : Math.round(latencyMs);
    }

    private void sample() {
        AudioTrack track = currentTrack();
        if (track == null || track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            // Paused, remote (Connect) or not created: nothing local is waiting to be heard.
            latencyMs = -1f;
            return;
        }
        int rate = track.getSampleRate();
        if (rate <= 0 || !track.getTimestamp(timestamp)) return;
        long head = track.getPlaybackHeadPosition() & 0xffffffffL;
        long presented = timestamp.framePosition
                + (System.nanoTime() - timestamp.nanoTime) * rate / 1_000_000_000L;
        float measured = (head - presented) * 1000f / rate;
        // Right after a start or flush the two counters briefly disagree; skip those samples.
        if (measured < 0f || measured > MAX_LATENCY_MS) return;
        latencyMs = latencyMs < 0f ? measured : latencyMs + (measured - latencyMs) * 0.3f;
        if (!logged) {
            logged = true;
            XpLog.log(NativeSpicyLyricsHook.TAG + " audio output latency " + Math.round(measured)
                    + "ms at " + rate + "Hz");
        }
    }

    private AudioTrack currentTrack() {
        try {
            if (!resolved) {
                resolved = true;
                Class<?> driver = Class.forName(DRIVER_CLASS, false, classLoader);
                sessionMapField = driver.getDeclaredField("sSessionToAudioDriverMap");
                sessionMapField.setAccessible(true);
                currentSessionField = driver.getDeclaredField("sCurrentAudioSession");
                currentSessionField.setAccessible(true);
                getAudioTrack = driver.getMethod("getAudioTrack");
            }
            if (sessionMapField == null) return null;
            Object session = currentSessionField.get(null);
            SparseArray<?> drivers = (SparseArray<?>) sessionMapField.get(null);
            if (drivers == null) return null;
            Object[] candidates;
            synchronized (drivers) {
                if (session instanceof Integer) {
                    candidates = new Object[]{drivers.get((Integer) session)};
                } else {
                    candidates = new Object[drivers.size()];
                    for (int i = 0; i < candidates.length; i++) candidates[i] = drivers.valueAt(i);
                }
            }
            AudioTrack fallback = null;
            for (Object driver : candidates) {
                Object track = driver == null ? null : getAudioTrack.invoke(driver);
                if (!(track instanceof AudioTrack)) continue;
                if (((AudioTrack) track).getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    return (AudioTrack) track;
                }
                fallback = (AudioTrack) track;
            }
            return fallback;
        } catch (Throwable t) {
            if (sessionMapField != null || !logged) {
                logged = true;
                XpLog.log(NativeSpicyLyricsHook.TAG + " audio output latency unavailable: "
                        + t.getClass().getSimpleName());
            }
            sessionMapField = null;
            return null;
        }
    }
}
