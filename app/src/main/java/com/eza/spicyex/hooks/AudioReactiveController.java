package com.eza.spicyex.hooks;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Live audio analysis for the beat-reactive background and the instrumental visualizer, read
 * straight from the PCM Spotify hands to {@link AudioTrack#write}.
 *
 * <p>An earlier version attached an {@link android.media.audiofx.Visualizer} to Spotify's audio
 * session. That works, but a Visualizer is audio capture as far as Android is concerned: it
 * needs the microphone permission and can light the privacy indicator. Every sample Spotify
 * plays already passes through this process on its way to the AudioTrack, so it is analysed
 * there - no capture, no permission, no extra audio pipeline.
 *
 * <p>Two kinds of output:
 * <ul>
 *   <li>drum onsets - every sample goes through {@link BeatTracker}, which pulls kicks and
 *       snares/claps out of the mix and leaves vocals, pads and sustained bass alone. Read live
 *       ({@link #beatNow}, {@link #accentNow}) against a timeline of when each bit of audio is
 *       heard, since hits are ~10ms events and the frame loop wants this frame's value;</li>
 *   <li>at most ~30 readings a second from one {@link #WINDOW}-frame window: loudness (plain
 *       RMS, "audio is playing" vs silence and the song's energy) and a spectrum of
 *       {@link #BANDS} log-spaced bands, each auto-gained against its own recent peak so quiet
 *       and loud tracks both fill the visualizer.</li>
 * </ul>
 * Only while the lyrics screen wants it ({@link #setListeningEnabled}). Writes run ahead of what
 * is audible by the track's buffer, so each reading is delivered that much later to line up with
 * the sound.
 */
final class AudioReactiveController {
    private static final String TAG = "[SpicyAudioReactive]";
    private static final long MIN_INTERVAL_MS = 33L;
    static final int BANDS = 28;
    private static final int WINDOW = 1024;
    private static final long MAX_ALIGN_DELAY_MS = 400L;
    private static final float MIN_HZ = 40f;
    private static final float MAX_HZ = 14000f;

    interface Listener {
        /** Main thread. {@code spectrum} is a fresh array the receiver may keep. */
        void onAnalysis(float loudness, float beat, float[] spectrum);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean listeningEnabled;
    private boolean started;

    // Analysis state, audio-thread only.
    private final float[] mono = new float[WINDOW];
    private final float[] re = new float[WINDOW];
    private final float[] im = new float[WINDOW];
    private final float[] hann = new float[WINDOW];
    private final float[] bandPeak = new float[BANDS];

    AudioReactiveController(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
        }
        java.util.Arrays.fill(bandPeak, 1e-3f);
    }

    /** Called from the lyrics screen's own mount/unmount lifecycle - see NativeSpicyLyricsHook. */
    void setListeningEnabled(boolean enabled) {
        listeningEnabled = enabled;
        if (!enabled) main.post(() -> listener.onAnalysis(0f, 0f, new float[BANDS]));
    }

    void start() {
        if (started) return;
        started = true;
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(byte[])",
                p -> onBytes((AudioTrack) p.thisObject, (byte[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                byte[].class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(byte[],mode)",
                p -> onBytes((AudioTrack) p.thisObject, (byte[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                byte[].class, int.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(short[])",
                p -> onShorts((AudioTrack) p.thisObject, (short[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                short[].class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(short[],mode)",
                p -> onShorts((AudioTrack) p.thisObject, (short[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                short[].class, int.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(float[])",
                p -> onFloats((AudioTrack) p.thisObject, (float[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                float[].class, int.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(ByteBuffer)",
                p -> onBuffer((AudioTrack) p.thisObject, (ByteBuffer) p.args[0], (int) p.args[1]),
                ByteBuffer.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(ByteBuffer,ts)",
                p -> onBuffer((AudioTrack) p.thisObject, (ByteBuffer) p.args[0], (int) p.args[1]),
                ByteBuffer.class, int.class, int.class, long.class));
        XpLog.log(TAG + " AudioTrack#write analysis hooks installed");
    }

    /** One missing overload must not keep the others from being hooked. */
    private static void safely(Runnable install) {
        try {
            install.run();
        } catch (Throwable t) {
            XpLog.log(TAG + " write hook skipped: " + t.getClass().getSimpleName());
        }
    }

    // Whole write, downmixed to mono; grown on demand, audio-thread only.
    private float[] chunk = new float[8192];
    private static final int MAX_CHUNK_FRAMES = 1 << 16;

    private float[] chunk(int frames) {
        if (chunk.length < frames) chunk = new float[Integer.highestOneBit(frames - 1) << 1];
        return chunk;
    }

    private boolean listening(AudioTrack track) {
        return (listeningEnabled || AudioDebug.watching()) && track != null
                && !AdMusicPlayer.isOwnTrack(track);
    }

    private void onBytes(AudioTrack track, byte[] data, int offset, int size) {
        AudioDebug.WRITE_CALLS[0].incrementAndGet();
        if (data == null || size <= 0 || !listening(track)) return;
        try {
            int encoding = track.getAudioFormat();
            if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                int channels = Math.max(1, track.getChannelCount());
                int frames = Math.min(MAX_CHUNK_FRAMES, size / channels);
                float[] out = chunk(frames);
                for (int f = 0; f < frames; f++) {
                    float sum = 0f;
                    for (int c = 0; c < channels; c++) {
                        sum += ((data[offset + f * channels + c] & 0xFF) - 128) / 128f;
                    }
                    out[f] = sum / channels;
                }
                process(track, frames);
            } else {
                ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                fillFromBytes(track, view, offset, size, encoding == AudioFormat.ENCODING_PCM_FLOAT);
            }
        } catch (Throwable ignored) {
        }
    }

    private void onShorts(AudioTrack track, short[] data, int offset, int count) {
        AudioDebug.WRITE_CALLS[1].incrementAndGet();
        if (data == null || count <= 0 || !listening(track)) return;
        try {
            int channels = Math.max(1, track.getChannelCount());
            int frames = Math.min(MAX_CHUNK_FRAMES, count / channels);
            float[] out = chunk(frames);
            for (int f = 0; f < frames; f++) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) sum += data[offset + f * channels + c] / 32768f;
                out[f] = sum / channels;
            }
            process(track, frames);
        } catch (Throwable ignored) {
        }
    }

    private void onFloats(AudioTrack track, float[] data, int offset, int count) {
        AudioDebug.WRITE_CALLS[2].incrementAndGet();
        if (data == null || count <= 0 || !listening(track)) return;
        try {
            int channels = Math.max(1, track.getChannelCount());
            int frames = Math.min(MAX_CHUNK_FRAMES, count / channels);
            float[] out = chunk(frames);
            for (int f = 0; f < frames; f++) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) sum += data[offset + f * channels + c];
                out[f] = sum / channels;
            }
            process(track, frames);
        } catch (Throwable ignored) {
        }
    }

    /** Absolute reads from the buffer's current position; the write itself is untouched. */
    private void onBuffer(AudioTrack track, ByteBuffer buffer, int size) {
        AudioDebug.WRITE_CALLS[3].incrementAndGet();
        if (buffer == null || size <= 0 || !listening(track)) return;
        try {
            ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            fillFromBytes(track, view, buffer.position(), size,
                    track.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT);
        } catch (Throwable ignored) {
        }
    }

    private void fillFromBytes(AudioTrack track, ByteBuffer view, int start, int size, boolean isFloat) {
        int channels = Math.max(1, track.getChannelCount());
        int bytesPerSample = isFloat ? 4 : 2;
        int frameBytes = channels * bytesPerSample;
        int frames = Math.min(MAX_CHUNK_FRAMES, Math.min(size, view.limit() - start) / frameBytes);
        float[] out = chunk(frames);
        for (int f = 0; f < frames; f++) {
            int base = start + f * frameBytes;
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                int at = base + c * bytesPerSample;
                sum += isFloat ? view.getFloat(at) : view.getShort(at) / 32768f;
            }
            out[f] = sum / channels;
        }
        process(track, frames);
    }

    /**
     * Every write: the beat tracker runs over ALL of it (Spotify hands over large chunks, and
     * looking only at the first 1024 frames of each - as before - saw a kick only when one
     * happened to land in that sliver, so real songs barely pulsed). The FFT spectrum and
     * loudness are read every {@link #MIN_INTERVAL_MS} of audio across the whole chunk, each
     * delivered when that part of the chunk is heard. Reading only the chunk's head gave one
     * reading per write - a few a second, in bursts - which is what made the visualizer jerk.
     */
    private void process(AudioTrack track, int frames) {
        if (frames <= 0) return;
        int sampleRate = Math.max(8000, track.getSampleRate());
        long startsAt = SystemClock.uptimeMillis() + latencyMs(track);
        // Keep the timeline monotonic: consecutive writes are consecutive audio, so a chunk that
        // the estimate places slightly before the previous one's end simply follows it.
        if (startsAt < timelineEnd && timelineEnd - startsAt < 400L) startsAt = timelineEnd;
        timelineEnd = startsAt + frames * 1000L / sampleRate;
        beats.process(chunk, frames, sampleRate, startsAt);
        if (!listeningEnabled && !AudioDebug.watching()) return;
        // A jump back (seek, new track) restarts the reading clock.
        if (nextReadingAt - startsAt > 1000L) nextReadingAt = 0L;
        int hop = Math.max(64, (int) (sampleRate * MIN_INTERVAL_MS / 1000L));
        for (int offset = 0; offset + 64 <= frames; offset += hop) {
            long heardAt = startsAt + offset * 1000L / sampleRate;
            if (heardAt < nextReadingAt) continue;
            nextReadingAt = heardAt + MIN_INTERVAL_MS;
            int n = Math.min(WINDOW, frames - offset);
            System.arraycopy(chunk, offset, mono, 0, n);
            analyse(track, n, heardAt);
        }
    }

    /** Audio-timeline time of the next spectrum reading; audio-thread only. */
    private long nextReadingAt;

    private final BeatTracker beats = new BeatTracker();
    private long timelineEnd;

    /** Kick envelope (0..1) for the audio being heard right now. Any thread. */
    float beatNow() {
        return sampleBeats()[0];
    }

    /** Snare/clap envelope (0..1) for the audio being heard right now. Any thread. */
    float accentNow() {
        return sampleBeats()[1];
    }

    private float[] sampleBeats() {
        float[] out = new float[2];
        if (listeningEnabled || AudioDebug.watching()) beats.sample(SystemClock.uptimeMillis(), out);
        return out;
    }

    private static long latencyMs(AudioTrack track) {
        try {
            int rate = track.getSampleRate();
            if (rate > 0) return Math.min(MAX_ALIGN_DELAY_MS, track.getBufferSizeInFrames() * 1000L / rate);
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private void analyse(AudioTrack track, int frames, long heardAt) {
        if (frames < 64) return;
        int sampleRate = Math.max(8000, track.getSampleRate());
        if (AudioDebug.watching()) {
            int encoding = track.getAudioFormat();
            AudioDebug.format = (encoding == AudioFormat.ENCODING_PCM_FLOAT ? "PCM float"
                    : encoding == AudioFormat.ENCODING_PCM_8BIT ? "PCM 8-bit" : "PCM 16-bit")
                    + " · " + sampleRate + " Hz · " + track.getChannelCount() + " ch";
        }
        double sumSquares = 0;
        for (int i = 0; i < frames; i++) sumSquares += mono[i] * mono[i];
        float loudness = Math.min(1f, (float) Math.sqrt(sumSquares / frames) * 2.7f);

        // Windowed FFT; a short write is zero-padded.
        for (int i = 0; i < WINDOW; i++) {
            re[i] = i < frames ? mono[i] * hann[i] : 0f;
            im[i] = 0f;
        }
        BeatTracker.fft(re, im);
        float binHz = sampleRate / (float) WINDOW;

        float[] spectrum = new float[BANDS];
        double logMin = Math.log(MIN_HZ);
        double logSpan = Math.log(Math.min(MAX_HZ, sampleRate / 2f)) - logMin;
        for (int b = 0; b < BANDS; b++) {
            int from = Math.max(1, (int) (Math.exp(logMin + logSpan * b / BANDS) / binHz));
            int to = Math.max(from + 1, (int) (Math.exp(logMin + logSpan * (b + 1) / BANDS) / binHz));
            float energy = 0f;
            for (int k = from; k < to && k < WINDOW / 2; k++) energy += re[k] * re[k] + im[k] * im[k];
            float magnitude = (float) Math.sqrt(energy / (to - from));
            // Auto-gain per band: its recent peak decays slowly (half in ~8s at 30 readings a
            // second), so a quiet passage still fills the visualizer and a loud one does not
            // pin it at the top.
            bandPeak[b] = Math.max(magnitude, bandPeak[b] * 0.997f);
            spectrum[b] = bandPeak[b] <= 1e-6f ? 0f
                    : (float) Math.pow(Math.min(1f, magnitude / bandPeak[b]), 1.4);
        }
        deliver(loudness, spectrum, heardAt);
    }

    private void deliver(float loudness, float[] spectrum, long heardAt) {
        main.postAtTime(() -> {
            float beatNow = beatNow();
            AudioDebug.loudness = loudness;
            AudioDebug.beat = beatNow;
            AudioDebug.accent = accentNow();
            AudioDebug.spectrum = spectrum;
            if (listeningEnabled) listener.onAnalysis(loudness, beatNow, spectrum);
        }, heardAt);
    }
}
