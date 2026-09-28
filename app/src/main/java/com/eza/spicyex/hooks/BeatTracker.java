package com.eza.spicyex.hooks;

/**
 * Drum onsets for the beat-reactive background, pulled out of the full mix. Pure Java so it can be
 * tested on synthetic audio; {@link AudioReactiveController} feeds it every sample Spotify writes,
 * on the audio thread, and reads it from the UI thread.
 *
 * <p>Two envelopes (0..1), each timestamped by when that audio is heard:
 * <ul>
 *   <li><b>kick</b> - onsets in the ~35-110 Hz band, from a time-domain band-pass and an energy
 *       follower sampled every ~10ms. Kept below where most voices have their fundamental, so a
 *       singer cannot pump it;</li>
 *   <li><b>accent</b> - snare, clap and similar hits, from an FFT per hop. A drum hit is broadband
 *       and sharp; a sung note or a pad is harmonic and swells in over tens of milliseconds. So an
 *       accent needs most bands to rise at once (a note lifts only the bands its partials sit
 *       in) and, at the same moment, the 3.5-12 kHz air band, where voices carry little except
 *       sibilants - and a sibilant alone lifts no mids.</li>
 * </ul>
 * Rises are measured in dB, so quiet and loud masters behave alike, and each detector's trigger
 * threshold follows the song's own typical rise (plus a floor), so a busy track needs a real hit
 * to fire and a sparse one still reads. After firing, a short refractory time keeps one hit from
 * reading as several.
 */
final class BeatTracker {
    static final int WINDOW = 1024;
    private static final float HOP_SECONDS = 0.010f;
    private static final float KICK_LOW_HZ = 35f;
    private static final float KICK_HIGH_HZ = 150f;
    private static final float KICK_FOLLOW_SEC = 0.010f;
    private static final int ACCENT_BANDS = 16;
    private static final float ACCENT_LOW_HZ = 150f;
    private static final float ACCENT_HIGH_HZ = 14000f;
    private static final float AIR_LOW_HZ = 3500f;
    private static final float AIR_HIGH_HZ = 12000f;
    /** Below this full-band level (dBFS) nothing triggers: fades, gaps, noise floor. */
    private static final float SILENCE_DB = -52f;
    /**
     * How far below its own running level a band is still measured. Deeper than this reads as
     * this floor: in dB, a sound starting from near-silence would otherwise "rise" by 60-100 dB
     * however gently it swells in, and a sung phrase after a breath would hit like a drum.
     */
    private static final float DEPTH_DB = 22f;
    /** Same, against the loudest band's running level: a band this far under it is inaudible. */
    private static final float MIX_DEPTH_DB = 45f;
    /**
     * The air band only counts from this far under the loudest band: a snare's noise reaches it,
     * the faint upper partials of a sung note (20+ harmonics up, 25-35 dB down) do not.
     */
    private static final float AIR_DEPTH_DB = 24f;
    /**
     * The kick band only counts when it is at most this far under the whole mix. With no bass or
     * drums in the song (a cappella, a piano ballad), what is left down there is the skirt of the
     * voice and must not pump the background.
     */
    private static final float KICK_DEPTH_DB = 20f;
    /**
     * Which band rise stands for "the whole spectrum rose": the 62nd percentile, so a hit must
     * lift ~10 of the 16 bands. A sung note moves the few bands its partials sit in; a drum
     * moves nearly all of them, even with a loud vocal on top.
     */
    private static final int ACCENT_RANK = 10;

    // ---- kick: band-pass + energy follower
    private float lpA1, lpA2, lpA3, lpA4, lpB1, lpB2;
    private float kickPower;
    private final float[] kickHistory = new float[4];
    private float kickLevelAvg = Float.NaN;
    private float mixLevelAvg = Float.NaN;

    // ---- accent: sliding FFT window
    private final float[] ring = new float[WINDOW];
    private int ringPos;
    private final float[] hann = new float[WINDOW];
    private final float[] re = new float[WINDOW];
    private final float[] im = new float[WINDOW];
    private final float[] bandDb = new float[ACCENT_BANDS];
    private final float[] prevBandDb = new float[ACCENT_BANDS];
    private final float[] prev2BandDb = new float[ACCENT_BANDS];
    private final float[] flux = new float[ACCENT_BANDS];
    private final float[] bandAvgDb = new float[ACCENT_BANDS];
    private float airAvgDb = Float.NaN;
    private final int[] bandFrom = new int[ACCENT_BANDS + 1];
    private int airFrom, airTo;
    private float prevAirDb = Float.NaN, prev2AirDb = Float.NaN;
    private int prevBandHops;
    private int bandsForRate;

    // ---- hop clock
    private int hop;
    private int sinceHop;
    private double hopPower;
    private long hopIndex;

    private final Onset kick = new Onset(1.2f, 0.11f, 0.20f);
    private final Onset accent = new Onset(1.6f, 0.08f, 0.13f);

    // Heard-at timeline of both envelopes, written on the audio thread, read on any.
    private static final int TIMELINE = 512;
    private final long[] timelineAt = new long[TIMELINE];
    private final float[] timelineKick = new float[TIMELINE];
    private final float[] timelineAccent = new float[TIMELINE];
    private int timelineHead = -1;

    BeatTracker() {
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
        }
    }

    /** Mono samples in -1..1, heard from {@code startsAtMs} on (uptime ms). Audio thread. */
    void process(float[] x, int frames, int sampleRate, long startsAtMs) {
        if (frames <= 0 || sampleRate <= 0) return;
        if (bandsForRate != sampleRate) layoutBands(sampleRate);
        float aHigh = onePole(KICK_HIGH_HZ, sampleRate);
        float aLow = onePole(KICK_LOW_HZ, sampleRate);
        float follow = 1f - (float) Math.exp(-1.0 / (KICK_FOLLOW_SEC * sampleRate));
        for (int i = 0; i < frames; i++) {
            float v = x[i];
            // 35-110 Hz: a 4-pole low-pass (a 200 Hz voice fundamental is ~20 dB down) minus a
            // 2-pole one that takes out rumble below the kick.
            lpA1 += (v - lpA1) * aHigh;
            lpA2 += (lpA1 - lpA2) * aHigh;
            lpA3 += (lpA2 - lpA3) * aHigh;
            lpA4 += (lpA3 - lpA4) * aHigh;
            lpB1 += (lpA4 - lpB1) * aLow;
            lpB2 += (lpB1 - lpB2) * aLow;
            float band = lpA4 - lpB2;
            // Energy follower rather than a per-hop sum: a 10ms hop is shorter than one cycle of a
            // 60 Hz kick, so a plain sum ripples with the waveform and reads as false rises.
            kickPower += (band * band - kickPower) * follow;
            hopPower += v * v;
            ring[ringPos] = v;
            ringPos = (ringPos + 1) & (WINDOW - 1);
            if (++sinceHop < hop) continue;
            float levelDb = db((float) (hopPower / sinceHop));
            sinceHop = 0;
            hopPower = 0;
            hopIndex++;
            boolean audible = levelDb > SILENCE_DB;
            if (audible) mixLevelAvg = follow(mixLevelAvg, levelDb, 0.4f);
            float k = kick.step(kickRise(audible), audible, hopIndex);
            float a = accent.step(accentRise(audible), audible, hopIndex);
            long at = startsAtMs + Math.round(1000.0 * (i + 1) / sampleRate);
            synchronized (timelineAt) {
                timelineHead = (timelineHead + 1) % TIMELINE;
                timelineAt[timelineHead] = at;
                timelineKick[timelineHead] = k;
                timelineAccent[timelineHead] = a;
            }
        }
    }

    /** Kick and accent envelopes for the audio heard at {@code nowMs}, into {@code out[0..1]}. */
    void sample(long nowMs, float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        synchronized (timelineAt) {
            if (timelineHead < 0) return;
            for (int n = 0, i = timelineHead; n < TIMELINE; n++, i = (i - 1 + TIMELINE) % TIMELINE) {
                long at = timelineAt[i];
                if (at == 0L) return;
                if (at <= nowMs) {
                    // Stale timeline (playback stopped): fall silent.
                    if (nowMs - at > 250L) return;
                    out[0] = timelineKick[i];
                    out[1] = timelineAccent[i];
                    return;
                }
            }
        }
    }

    private float kickRise(boolean audible) {
        float rawDb = db(kickPower);
        if (audible) kickLevelAvg = follow(kickLevelAvg, rawDb, 1f);
        if (Float.isNaN(kickLevelAvg)) return 0f;
        float levelDb = Math.max(rawDb, kickLevelAvg - DEPTH_DB);
        // Rise against the lowest of the last few hops: a kick's 10-30ms attack registers even on
        // a mix whose bassline never lets the low end fall silent.
        float valley = Math.min(Math.min(kickHistory[0], kickHistory[1]), kickHistory[2]);
        System.arraycopy(kickHistory, 0, kickHistory, 1, kickHistory.length - 1);
        kickHistory[0] = levelDb;
        // A "rise" well below the song's usual low end is room noise or a bass tail, not a kick.
        if (levelDb < kickLevelAvg - 10f || !(levelDb >= mixLevelAvg - KICK_DEPTH_DB)) return 0f;
        // And a kick lands above the low end's usual level. Recovering from a dip - a bass note
        // beating against a kick's tail, a gap in the bassline - rises without getting there.
        return Math.max(0f, Math.min(levelDb - valley, levelDb - kickLevelAvg + 3f));
    }

    private float accentRise(boolean audible) {
        for (int i = 0, p = ringPos; i < WINDOW; i++, p = (p + 1) & (WINDOW - 1)) {
            re[i] = ring[p] * hann[i];
            im[i] = 0f;
        }
        fft(re, im);
        float loudestAvg = -300f;
        for (int b = 0; b < ACCENT_BANDS; b++) {
            float raw = db(meanPower(bandFrom[b], bandFrom[b + 1]));
            if (audible || Float.isNaN(bandAvgDb[b])) bandAvgDb[b] = follow(bandAvgDb[b], raw, 1f);
            loudestAvg = Math.max(loudestAvg, bandAvgDb[b]);
            bandDb[b] = raw;
        }
        float rawAir = db(meanPower(airFrom, airTo));
        if (audible || Float.isNaN(airAvgDb)) airAvgDb = follow(airAvgDb, rawAir, 1f);
        // A band that is normally near-empty (nothing up there in this song) only counts from
        // where it could be heard next to the rest of the mix.
        float mixFloor = loudestAvg - MIX_DEPTH_DB;
        for (int b = 0; b < ACCENT_BANDS; b++) {
            bandDb[b] = Math.max(bandDb[b], Math.max(bandAvgDb[b] - DEPTH_DB, mixFloor));
        }
        float airDb = Math.max(rawAir, Math.max(airAvgDb - DEPTH_DB, loudestAvg - AIR_DEPTH_DB));
        float result = 0f;
        if (prevBandHops >= 2) {
            for (int b = 0; b < ACCENT_BANDS; b++) {
                // Against the loudest neighbour two hops ago (SuperFlux): the 21ms window spreads
                // a hit over two hops, and vibrato and gliding notes move energy between adjacent
                // bands without a rise showing up.
                float ref = prev2BandDb[b];
                if (b > 0) ref = Math.max(ref, prev2BandDb[b - 1]);
                if (b + 1 < ACCENT_BANDS) ref = Math.max(ref, prev2BandDb[b + 1]);
                flux[b] = Math.max(0f, bandDb[b] - ref);
            }
            java.util.Arrays.sort(flux);
            float broad = flux[ACCENT_RANK];
            float air = Math.max(0f, airDb - prev2AirDb);
            result = Math.min(broad, air);
        }
        System.arraycopy(prevBandDb, 0, prev2BandDb, 0, ACCENT_BANDS);
        System.arraycopy(bandDb, 0, prevBandDb, 0, ACCENT_BANDS);
        prev2AirDb = prevAirDb;
        prevAirDb = airDb;
        prevBandHops++;
        return result;
    }

    private float meanPower(int from, int to) {
        double sum = 0;
        for (int k = from; k < to; k++) sum += re[k] * re[k] + im[k] * im[k];
        return (float) (sum / Math.max(1, to - from));
    }

    private void layoutBands(int sampleRate) {
        bandsForRate = sampleRate;
        hop = Math.max(64, Math.round(sampleRate * HOP_SECONDS));
        float binHz = sampleRate / (float) WINDOW;
        int maxBin = WINDOW / 2;
        double logLow = Math.log(ACCENT_LOW_HZ);
        double logSpan = Math.log(Math.min(ACCENT_HIGH_HZ, sampleRate * 0.45f)) - logLow;
        for (int b = 0; b <= ACCENT_BANDS; b++) {
            int bin = (int) Math.round(Math.exp(logLow + logSpan * b / ACCENT_BANDS) / binHz);
            bandFrom[b] = Math.max(1, Math.min(maxBin, bin));
            if (b > 0 && bandFrom[b] <= bandFrom[b - 1]) bandFrom[b] = Math.min(maxBin, bandFrom[b - 1] + 1);
        }
        airFrom = Math.max(1, Math.min(maxBin - 1, Math.round(AIR_LOW_HZ / binHz)));
        airTo = Math.max(airFrom + 1, Math.min(maxBin, Math.round(Math.min(AIR_HIGH_HZ, sampleRate * 0.45f) / binHz)));
        prevBandHops = 0;
        java.util.Arrays.fill(bandAvgDb, Float.NaN);
        airAvgDb = Float.NaN;
    }

    /**
     * Running level in dB: catches up within {@code riseSec} when the music gets louder (a quiet
     * intro must not leave every later hit measured against near-silence) and lets go over ~3s.
     */
    private static float follow(float avg, float value, float riseSec) {
        if (Float.isNaN(avg)) return value;
        float tau = value > avg ? riseSec : 3f;
        return avg + (value - avg) * (HOP_SECONDS / tau);
    }

    private static float db(float power) {
        return 10f * (float) Math.log10(power + 1e-12f);
    }

    private static float onePole(float cutoffHz, int sampleRate) {
        return (float) (1.0 - Math.exp(-2.0 * Math.PI * cutoffHz / sampleRate));
    }

    /**
     * One detector: an adaptive threshold over its rise (dB per hop), a refractory time and a
     * decaying envelope.
     */
    private static final class Onset {
        private final float floorDb;
        private final float refractorySec;
        private final float decay;
        // Typical rise and its spread, over ~1.5s of audible audio. Seeded so the first second
        // of a song does not treat every rise as a hit.
        private float mean = 1.5f;
        private float spread = 1.5f;
        private float envelope;
        private long triggeredHop = Long.MIN_VALUE / 2;

        Onset(float floorDb, float refractorySec, float decaySec) {
            this.floorDb = floorDb;
            this.refractorySec = refractorySec;
            this.decay = (float) Math.exp(-HOP_SECONDS / decaySec);
        }

        float step(float rise, boolean audible, long hop) {
            float strength = 0f;
            if (audible) {
                float threshold = mean + 1.3f * spread + floorDb;
                float over = (rise - threshold) / (spread + 2f);
                strength = over <= 0f ? 0f : (float) Math.pow(Math.min(1f, over), 0.75);
                float rate = HOP_SECONDS / 1.5f;
                mean += (rise - mean) * rate;
                spread += (Math.abs(rise - mean) - spread) * rate;
            }
            if (strength > 0f) {
                float since = (hop - triggeredHop) * HOP_SECONDS;
                // A hit's attack can span two or three hops: those still count toward the same
                // hit. Past that, nothing new fires until the refractory time is over.
                if (since >= refractorySec) triggeredHop = hop;
                else if (since > 0.03f) strength = 0f;
            }
            envelope = Math.max(strength, envelope * decay);
            if (envelope < 1e-3f) envelope = 0f;
            return envelope;
        }
    }

    /** In-place iterative radix-2 FFT; {@code re.length} must be a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            float wr = (float) Math.cos(angle);
            float wi = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float cr = 1f;
                float ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    float tr = re[b] * cr - im[b] * ci;
                    float ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
