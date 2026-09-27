package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/**
 * BeatTracker on synthetic songs: a kick/snare groove must come through under a sung line, and a
 * sung line on its own (legato phrases, breaths, an "s", a pad underneath) must not.
 */
public class BeatTrackerTest {
    private static final int RATE = 48000;

    @Test
    public void kicksAndSnaresComeThroughUnderVocals() {
        Song song = new Song(10);
        song.vocals(0.2, 10, 200);
        for (double t = 0; t < 10; t += 2) song.bass(t, 1.9, 55, 0.3f);
        for (double t = 0.1; t < 10; t += 1.0) {
            song.kick(t, 0.8f);
            song.snare(t + 0.5, 0.5f);
        }
        float[][] env = song.run(RATE, 4096);
        int kicks = 0, snares = 0, total = 0;
        for (double t = 2.1; t < 9.5; t += 1.0) {
            total++;
            if (max(env[0], t, t + 0.06) > 0.5f) kicks++;
            if (max(env[1], t + 0.5, t + 0.56) > 0.4f) snares++;
            // Each drum drives its own envelope only.
            assertTrue("accent at kick " + t, max(env[1], t, t + 0.06) < 0.2f);
            float kickBefore = env[0][(int) ((t + 0.49) * 1000)];
            assertTrue("kick at snare " + t, max(env[0], t + 0.5, t + 0.56) - kickBefore < 0.2f);
        }
        assertTrue("kicks " + kicks + "/" + total, kicks >= total - 1);
        assertTrue("snares " + snares + "/" + total, snares >= total - 1);
    }

    @Test
    public void vocalsAloneDoNotPulse() {
        for (double f0 : new double[]{200, 300}) {
            Song song = new Song(10);
            song.vocals(0.2, 10, f0);
            float[][] env = song.run(RATE, 4096);
            assertTrue("kick f0=" + f0, max(env[0], 1.5, 10) < 0.2f);
            assertTrue("accent f0=" + f0, max(env[1], 1.5, 10) < 0.2f);
        }
    }

    @Test
    public void vocalsOverABasslineDoNotFlashTheAccent() {
        Song song = new Song(10);
        song.vocals(0.2, 10, 200);
        for (double t = 0; t < 10; t += 2) song.bass(t, 1.9, 55, 0.3f);
        assertTrue(max(song.run(RATE, 4096)[1], 1.5, 10) < 0.2f);
    }

    @Test
    public void detectionDoesNotDependOnWriteSizeOrRate() {
        Song song = new Song(6);
        for (double t = 0.1; t < 6; t += 0.5) song.kick(t, 0.8f);
        song.pad(0, 6);
        for (int[] setup : new int[][]{{48000, 960}, {44100, 3528}, {44100, 16384}}) {
            Song resampled = song.resampled(setup[0]);
            float[][] env = resampled.run(setup[0], setup[1]);
            for (double t = 2.1; t < 5.5; t += 0.5) {
                assertTrue("kick " + t + " @" + setup[0] + "/" + setup[1], max(env[0], t, t + 0.06) > 0.5f);
            }
        }
    }

    @Test
    public void silenceAndAStoppedTimelineReadZero() {
        Song song = new Song(3);
        float[][] env = song.run(RATE, 4096);
        assertEquals(0f, max(env[0], 0, 3), 0f);
        assertEquals(0f, max(env[1], 0, 3), 0f);

        BeatTracker tracker = new BeatTracker();
        Song beat = new Song(1);
        beat.kick(0.5, 0.8f);
        tracker.process(beat.x, beat.x.length, RATE, 1000L);
        float[] out = new float[2];
        tracker.sample(1000L + 10_000L, out);
        assertEquals(0f, out[0], 0f);
    }

    private static float max(float[] v, double from, double to) {
        float m = 0f;
        for (int i = (int) (from * 1000); i < Math.min(v.length, to * 1000); i++) m = Math.max(m, v[i]);
        return m;
    }

    private static final class Song {
        final float[] x;
        final int rate;
        private final Random random = new Random(7);

        Song(double seconds) {
            this(new float[(int) (seconds * RATE)], RATE);
        }

        private Song(float[] x, int rate) {
            this.x = x;
            this.rate = rate;
        }

        Song resampled(int newRate) {
            float[] out = new float[(int) ((long) x.length * newRate / rate)];
            for (int i = 0; i < out.length; i++) {
                double at = i * (double) rate / newRate;
                int j = (int) at;
                double f = at - j;
                out[i] = (float) (x[Math.min(j, x.length - 1)] * (1 - f) + x[Math.min(j + 1, x.length - 1)] * f);
            }
            return new Song(out, newRate);
        }

        /** Pitch-dropping sine thump: 120 Hz falling to 50 Hz, 150ms decay. */
        void kick(double at, float amp) {
            int s = (int) (at * rate);
            double phase = 0;
            for (int i = 0; i < rate / 2 && s + i < x.length; i++) {
                double t = i / (double) rate;
                phase += 2 * Math.PI * (50 + 70 * Math.exp(-t / 0.03)) / rate;
                x[s + i] += (float) (amp * Math.sin(phase) * Math.exp(-t / 0.15) * Math.min(1, t / 0.002));
            }
        }

        /** Noise burst plus a 190 Hz body. */
        void snare(double at, float amp) {
            int s = (int) (at * rate);
            for (int i = 0; i < rate * 0.4 && s + i < x.length; i++) {
                double t = i / (double) rate;
                x[s + i] += (float) (Math.min(1, t / 0.001) * amp
                        * ((random.nextFloat() * 2 - 1) * Math.exp(-t / 0.08)
                        + 0.6 * Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t / 0.05)));
            }
        }

        /** A sung note: 30 partials with two formants, vibrato, 50ms swell. */
        void note(double at, double dur, double f0, float amp) {
            int s = (int) (at * rate);
            double phase = 0;
            for (int i = 0; i < dur * rate && s + i < x.length; i++) {
                double t = i / (double) rate;
                double env = Math.min(1, t / 0.05) * Math.min(1, (dur - t) / 0.06);
                double f = f0 * (1 + 0.02 * Math.sin(2 * Math.PI * 5.5 * t) * Math.min(1, t / 0.3));
                phase += 2 * Math.PI * f / rate;
                double v = 0;
                for (int n = 1; n <= 30 && n * f < 16000; n++) {
                    double formant = 1 + 3 * Math.exp(-Math.pow((n * f - 700) / 300, 2))
                            + 2 * Math.exp(-Math.pow((n * f - 2500) / 500, 2));
                    v += Math.sin(n * phase) / n * formant;
                }
                x[s + i] += (float) (amp * env * v * 0.3);
            }
        }

        /** An "s": high-passed noise, 40ms swell. */
        void sibilant(double at, double dur, float amp) {
            int s = (int) (at * rate);
            float previous = 0f;
            for (int i = 0; i < dur * rate && s + i < x.length; i++) {
                double t = i / (double) rate;
                float white = random.nextFloat() * 2 - 1;
                x[s + i] += (float) (amp * Math.min(1, t / 0.04) * Math.min(1, (dur - t) / 0.04)
                        * (white - previous) * 0.5);
                previous = white;
            }
        }

        void bass(double at, double dur, double f0, float amp) {
            int s = (int) (at * rate);
            for (int i = 0; i < dur * rate && s + i < x.length; i++) {
                double t = i / (double) rate;
                double env = Math.min(1, t / 0.03) * Math.min(1, (dur - t) / 0.03);
                x[s + i] += (float) (amp * env * (Math.sin(2 * Math.PI * f0 * t)
                        + 0.3 * Math.sin(4 * Math.PI * f0 * t)));
            }
        }

        /** Phrases of four legato syllables, a breath between phrases, over a pad. */
        void vocals(double from, double to, double f0) {
            double[] steps = {1, 1.122, 1.26, 1.335, 1.5, 1.335, 1.26, 1.122};
            int k = 0;
            for (double t = from; t < to; t += 1.8) {
                for (int syllable = 0; syllable < 4; syllable++) {
                    double at = t + syllable * 0.36;
                    note(at, syllable == 3 ? 0.5 : 0.40, f0 * steps[k++ % steps.length], 0.35f);
                    if (syllable == 2) sibilant(at + 0.30, 0.10, 0.25f);
                }
            }
            pad(0, x.length / (double) rate);
        }

        void pad(double from, double to) {
            int s = (int) (from * rate);
            for (int i = 0; i < (to - from) * rate && s + i < x.length; i++) {
                double t = i / (double) rate;
                double v = 0;
                for (double f : new double[]{220, 277.2, 329.6}) {
                    for (int n = 1; n <= 6; n++) v += Math.sin(2 * Math.PI * f * n * t + n) / (n * n);
                }
                x[s + i] += (float) (0.03 * v * Math.min(1, t / 0.5));
            }
        }

        /** Envelopes per heard millisecond, [0] kick and [1] accent, fed in writes of {@code chunk}. */
        float[][] run(int sampleRate, int chunk) {
            BeatTracker tracker = new BeatTracker();
            int ms = (int) ((long) x.length * 1000 / sampleRate);
            float[][] out = new float[2][ms];
            float[] buffer = new float[chunk];
            float[] sample = new float[2];
            int written = 0;
            int nextMs = 0;
            while (written < x.length) {
                int n = Math.min(chunk, x.length - written);
                System.arraycopy(x, written, buffer, 0, n);
                tracker.process(buffer, n, sampleRate, 1000L + written * 1000L / sampleRate);
                written += n;
                int heard = (int) (written * 1000L / sampleRate);
                for (; nextMs < heard && nextMs < ms; nextMs++) {
                    tracker.sample(1000L + nextMs, sample);
                    out[0][nextMs] = sample[0];
                    out[1][nextMs] = sample[1];
                }
            }
            return out;
        }
    }
}
