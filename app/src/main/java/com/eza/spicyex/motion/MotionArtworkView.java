package com.eza.spicyex.motion;

import android.content.Context;
import android.graphics.Matrix;
import android.view.TextureView;
import android.view.View;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.eza.spicyex.xposed.XpLog;

/**
 * A muted, looping, centre-cropped video surface for motion artwork (ExoPlayer, since Apple's
 * clips are HLS). It reports the first rendered frame, so the caller can cross over from the
 * still cover only once there is something to show, and a failure, so the caller keeps the still.
 * Retries once on the clip's fallback URL.
 */
public final class MotionArtworkView extends TextureView {
    private static final String TAG = "[SpotifyPlusMotionArt]";

    public interface Listener {
        /** The first frame of the current clip is on screen. */
        void onFirstFrame();

        /** The clip cannot be played; the still cover should stay. */
        void onFailed();
    }

    private ExoPlayer player;
    private MotionArtworkFinder.Clip clip;
    private boolean triedFallback;
    private boolean wantPlaying = true;
    private Listener listener;
    private int videoWidth;
    private int videoHeight;

    public MotionArtworkView(Context context) {
        super(context);
        setOpaque(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Starts (or switches to) {@code next}; null stops and clears. */
    public void setClip(MotionArtworkFinder.Clip next) {
        if (next == clip) return;
        releasePlayer();
        clip = next;
        triedFallback = false;
        if (next != null) start(next.url);
    }

    public void setPlaying(boolean playing) {
        wantPlaying = playing;
        if (player != null) player.setPlayWhenReady(playing && isShown());
    }

    public boolean hasClip() {
        return clip != null;
    }

    private void start(String url) {
        releasePlayer();
        try {
            final ExoPlayer exo = new ExoPlayer.Builder(getContext()).build();
            player = exo;
            exo.setVolume(0f);
            exo.setRepeatMode(Player.REPEAT_MODE_ONE);
            exo.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    if (player != exo) return;
                    XpLog.log(TAG + " first frame");
                    if (listener != null) listener.onFirstFrame();
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    if (player != exo) return;
                    videoWidth = size.width;
                    videoHeight = size.height;
                    fitVideo();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (player != exo) return;
                    XpLog.log(TAG + " playback error " + error.getErrorCodeName());
                    fail();
                }
            });
            exo.setVideoTextureView(this);
            exo.setMediaItem(MediaItem.fromUri(url));
            exo.setPlayWhenReady(wantPlaying);
            exo.prepare();
        } catch (RuntimeException e) {
            XpLog.log(TAG + " could not start: " + e);
            fail();
        }
    }

    private void fail() {
        releasePlayer();
        MotionArtworkFinder.Clip current = clip;
        if (current != null && !triedFallback && current.fallbackUrl != null) {
            triedFallback = true;
            start(current.fallbackUrl);
            return;
        }
        if (listener != null) listener.onFailed();
    }

    private void releasePlayer() {
        ExoPlayer p = player;
        player = null;
        videoWidth = 0;
        videoHeight = 0;
        if (p == null) return;
        try {
            p.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** Centre-crops the video to fill the view, the way the still cover is scaled. */
    private void fitVideo() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        // The texture is stretched to the view; undo that to the video's own aspect, then fill.
        float scale = Math.max(w / (float) videoWidth, h / (float) videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2f, h / 2f);
        setTransform(matrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitVideo();
    }

    /** Not decoding while it is off screen (lyrics opened over the player, another page). */
    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (player != null) player.setPlayWhenReady(isVisible && wantPlaying);
    }

    /** Frees the decoder; the view can be given a clip again afterwards. */
    public void release() {
        clip = null;
        releasePlayer();
    }
}
