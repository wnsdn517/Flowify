package com.flowify.ettea.lyrics;

import android.os.SystemClock;

import com.flowify.ettea.SpotifyTrack;
import static com.flowify.ettea.lyrics.LyricUtils.safe;

/** Smooths Spotify's coarse playback progress samples for lyrics animation. */
public final class LyricsPlaybackClock {
    private static final long[] RESYNC_TIMINGS_MS = new long[]{50, 100, 150, 750};
    private static final long STEADY_RESYNC_MS = 33;
    private static final long JITTER_RESYNC_THRESHOLD_MS = 500;
    private static final double JITTER_TIME_CONSTANT_MS = 300d;
    private static final long PROGRESS_POSITION_OFFSET_MS = 25;
    /** How long after a seek a far-off sample is taken for Spotify's stale pre-seek position. */
    static final long SEEK_SETTLE_MS = 2500;
    /** A sample this far from where the seek put us is from before the seek, not after it. */
    static final long SEEK_STALE_DISTANCE_MS = 1500;

    private final Measurer measurer;
    private String trackUri = "";
    private long sampledPositionMs = -1;
    private long sampledAtElapsedMs = 0;
    private long predictedPositionMs = -1;
    private long predictedUpdatedAtElapsedMs = 0;
    private long nextResyncAtElapsedMs = 0;
    private int syncIndex = 0;
    private long seekTargetMs = -1;
    /**
     * How long the audio takes to restart after a seek, learned. Measured on a Spicy Connect
     * device: Spotify reports the new position as if playback resumed at once, then about a
     * second later corrects itself back by 0.6-0.8 s - the time the audio actually took. The
     * lyrics hold at the target for this long instead of running ahead of the sound.
     */
    private static long restartDelayMs = 600;
    /** How long to wait for Spotify's correction before concluding there was no restart lag:
     *  a slow rebuffer was seen to take 3.3 s. */
    static final long RESTART_WATCH_MS = 4000;
    /** Whether playback is on a remote device (Spicy Connect, a speaker): the restart model is
     *  for those only - a local seek has no such gap, and holding would lag the lyrics instead. */
    private java.util.function.BooleanSupplier remotePlayback;

    public void setRemotePlayback(java.util.function.BooleanSupplier remote) {
        remotePlayback = remote;
    }

    /** The settling seek's restart has been confirmed (Spotify corrected, or never needed to). */
    private boolean restartSettled = true;
    /** Spotify's position stopped advancing (buffering): the lyrics wait instead of running on. */
    private boolean stalled;
    private long previousSampleMs = -1;
    private long previousSampleAtElapsedMs = 0;
    /** A far-off sample waiting for a second one to confirm it (-1: none). */
    private long pendingJumpMs = -1;
    private long pendingJumpAtElapsedMs = 0;
    /** Which measurement raised the pending jump: only a newer one can confirm it. */
    private long pendingJumpSampleAtMs = -1;
    private long seekAtElapsedMs = 0;

    public LyricsPlaybackClock(Measurer measurer) {
        this.measurer = measurer;
    }

    public void reset(String uri) {
        trackUri = safe(uri);
        sampledPositionMs = -1;
        sampledAtElapsedMs = 0;
        predictedPositionMs = -1;
        predictedUpdatedAtElapsedMs = 0;
        nextResyncAtElapsedMs = 0;
        syncIndex = 0;
        seekTargetMs = -1;
        seekTargetAnchorMs = -1;
        restartSettled = true;
        stalled = false;
        previousSampleMs = -1;
    }

    public void forcePosition(long positionMs, boolean playing) {
        forcePositionAt(positionMs, playing, SystemClock.elapsedRealtime());
    }

    /**
     * After a seek Spotify keeps reporting the old position for a moment. Resyncing to it snapped
     * the lyrics back to where they were, then forward again once the seek landed - the "it
     * drifts after seeking" feel. Until a sample near the target arrives (or the settle window
     * ends), far-off samples are ignored; the fast resync cadence restarts so the clock locks on
     * to the new position as soon as Spotify reports it.
     */
    void forcePositionAt(long positionMs, boolean playing, long now) {
        long clamped = clampToTrack(positionMs, null);
        sampledPositionMs = clamped;
        sampledAtElapsedMs = now;
        predictedPositionMs = clamped;
        predictedUpdatedAtElapsedMs = now;
        syncIndex = 0;
        seekTargetMs = clamped;
        seekTargetAnchorMs = clamped;
        seekAtElapsedMs = now;
        boolean remote = false;
        try {
            remote = remotePlayback != null && remotePlayback.getAsBoolean();
        } catch (Throwable ignored) {
        }
        restartSettled = !playing || !remote;
        previousSampleMs = clamped;
        previousSampleAtElapsedMs = now;
        stalled = false;
        pendingJumpMs = -1;
        nextResyncAtElapsedMs = now + nextDelayMs(playing, now);
    }

    /** Whether {@code measured} agrees with the earlier far-off sample, projected to now. */
    boolean confirmsPendingJump(long measuredNow, long now) {
        if (pendingJumpMs < 0 || now - pendingJumpAtElapsedMs > 1500) return false;
        long projected = pendingJumpMs + (now - pendingJumpAtElapsedMs);
        return Math.abs(measuredNow - projected) <= 400;
    }

    /** Whether {@code measured} is Spotify still reporting where it was before our seek. */
    boolean isStalePreSeekSample(long measured, boolean playing, long now) {
        if (seekTargetMs < 0) return false;
        if (now - seekAtElapsedMs > SEEK_SETTLE_MS) {
            seekTargetMs = -1;
            return false;
        }
        long expected = seekTargetMs + (playing ? Math.max(0, now - seekAtElapsedMs) : 0);
        boolean stale = Math.abs(measured - seekTargetMs) > SEEK_STALE_DISTANCE_MS
                && Math.abs(measured - expected) > SEEK_STALE_DISTANCE_MS;
        if (!stale) seekTargetMs = -1; // the seek has landed: back to normal following
        return stale;
    }

    public long getPosition(SpotifyTrack track, boolean playing) {
        String uri = track == null ? "" : safe(track.uri);
        if (!safe(trackUri).equals(uri)) reset(uri);

        long now = SystemClock.elapsedRealtime();
        if (sampledPositionMs < 0 || now >= nextResyncAtElapsedMs) {
            long measured = measure(track, playing);
            if (measured >= 0) applyMeasuredSample(track, measured, playing, now);
        }
        if (!restartSettled && playing && seekTargetAnchorMs >= 0) {
            // Restart model: hold at the target until the audio is expected to be playing.
            long model = seekTargetAnchorMs + Math.max(0, now - seekAtElapsedMs - restartDelayMs);
            predictedPositionMs = model;
            predictedUpdatedAtElapsedMs = now;
            return clampToTrack(model + PROGRESS_POSITION_OFFSET_MS, track);
        }

        if (sampledPositionMs < 0) {
            long fallback = measure(track, playing);
            return fallback < 0 ? -1 : clampToTrack(fallback + (playing ? PROGRESS_POSITION_OFFSET_MS : 0), track);
        }

        long measuredNow = sampledPositionMs;
        if (playing && !stalled) measuredNow += Math.max(0, now - sampledAtElapsedMs);
        measuredNow = clampToTrack(measuredNow, track);

        if (predictedPositionMs < 0 || !playing) {
            predictedPositionMs = measuredNow;
            predictedUpdatedAtElapsedMs = now;
            return clampToTrack(predictedPositionMs, track);
        }

        long elapsed = Math.max(0, now - predictedUpdatedAtElapsedMs);
        long predictedNow = clampToTrack(predictedPositionMs + elapsed, track);
        if (stalled && predictedNow > measuredNow) {
            // Buffering (measured after a seek: the position held ~0.8 s while the audio
            // restarted). Running on put the lyrics half a second ahead of the sound and then
            // snapped them back; hold where the audio is instead.
            predictedNow = Math.max(measuredNow, Math.min(predictedPositionMs, predictedNow));
        }
        long error = measuredNow - predictedNow;
        if (Math.abs(error) > JITTER_RESYNC_THRESHOLD_MS) {
            // One far-off sample is as often Spotify glitching (buffering, a stale report around
            // a track change) as a real seek; snapping to it moved the active line there and back,
            // and the scroll with it. Believe a jump once a second sample agrees with the first.
            if (sampledAtElapsedMs != pendingJumpSampleAtMs && confirmsPendingJump(measuredNow, now)) {
                predictedNow = measuredNow;
                pendingJumpMs = -1;
            } else if (sampledAtElapsedMs != pendingJumpSampleAtMs) {
                pendingJumpMs = measuredNow;
                pendingJumpAtElapsedMs = now;
                pendingJumpSampleAtMs = sampledAtElapsedMs;
                nextResyncAtElapsedMs = Math.min(nextResyncAtElapsedMs, now + STEADY_RESYNC_MS);
            }
        } else {
            pendingJumpMs = -1;
            double alpha = 1d - Math.exp(-(double) elapsed / JITTER_TIME_CONSTANT_MS);
            predictedNow = clampToTrack(Math.round(predictedNow + error * alpha), track);
        }

        predictedPositionMs = predictedNow;
        predictedUpdatedAtElapsedMs = now;
        long output = predictedNow + PROGRESS_POSITION_OFFSET_MS;
        return clampToTrack(output, track);
    }

    private long measure(SpotifyTrack track, boolean playing) {
        return measurer == null ? -1 : measurer.readBestMeasuredProgressMs(track, playing);
    }

    /** The settling seek's target; kept after the stale-sample guard has cleared. */
    private long seekTargetAnchorMs = -1;

    /**
     * While a seek's restart is unconfirmed: Spotify's correction (its report dropping well behind
     * "target + time since seek") is the real restart delay, learned and then followed; reports
     * that keep pace with the optimistic projection long past the expected delay mean there was
     * no restart lag, and the learned delay shrinks.
     */
    private void observeRestart(long measured, long now) {
        if (restartSettled || seekTargetAnchorMs < 0) return;
        long sinceSeek = now - seekAtElapsedMs;
        long optimistic = seekTargetAnchorMs + sinceSeek;
        long lag = optimistic - measured;
        if (lag > 250 && measured >= seekTargetAnchorMs - 200) {
            restartDelayMs = Math.max(0, Math.min(1500, Math.round(restartDelayMs * 0.5 + lag * 0.5)));
            restartSettled = true;
            predictedPositionMs = measured;
            predictedUpdatedAtElapsedMs = now;
        } else if (sinceSeek > RESTART_WATCH_MS) {
            // No correction came: this device restarts without a gap (decay gently - one slow
            // rebuffer that corrected late must not halve what was learned).
            restartDelayMs = Math.round(restartDelayMs * 0.8);
            restartSettled = true;
            predictedPositionMs = measured;
            predictedUpdatedAtElapsedMs = now;
        }
    }

    private void applyMeasuredSample(SpotifyTrack track, long measured, boolean playing, long now) {
        if (playing) observeRestart(clampToTrack(measured, track), now);
        if (isStalePreSeekSample(measured, playing, now)) {
            nextResyncAtElapsedMs = now + nextDelayMs(playing, now);
            return;
        }
        long clamped = clampToTrack(measured, track);
        // Stalled: the last two reports, far enough apart to tell, show the position advancing
        // at under half of real time while we are told it is playing.
        long gap = now - previousSampleAtElapsedMs;
        stalled = playing && previousSampleMs >= 0 && gap >= 25 && gap <= 2000
                && clamped - previousSampleMs < gap / 2;
        previousSampleMs = clamped;
        previousSampleAtElapsedMs = now;
        sampledPositionMs = clamped;
        sampledAtElapsedMs = now;
        if (predictedPositionMs < 0 || !playing) {
            predictedPositionMs = sampledPositionMs;
            predictedUpdatedAtElapsedMs = now;
        }
        nextResyncAtElapsedMs = now + nextDelayMs(playing, now);
    }

    private long nextDelayMs(boolean playing, long now) {
        if (!playing) return 250;
        // Right after a seek the audio restarts and may buffer: watch it closely the whole time
        // rather than leaving the usual 750 ms gap in which the lyrics ran ahead unchecked.
        if (seekAtElapsedMs > 0
                && now - seekAtElapsedMs < SEEK_SETTLE_MS) {
            return STEADY_RESYNC_MS;
        }
        long delay = syncIndex < RESYNC_TIMINGS_MS.length ? RESYNC_TIMINGS_MS[syncIndex] : STEADY_RESYNC_MS;
        if (syncIndex < RESYNC_TIMINGS_MS.length) syncIndex++;
        return delay;
    }

    private long clampToTrack(long positionMs, SpotifyTrack track) {
        long clamped = Math.max(0, positionMs);
        long duration = track == null ? 0 : Math.max(0, track.duration);
        if (duration > 0) clamped = Math.min(clamped, duration);
        return clamped;
    }

    public interface Measurer {
        long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing);
    }
}
