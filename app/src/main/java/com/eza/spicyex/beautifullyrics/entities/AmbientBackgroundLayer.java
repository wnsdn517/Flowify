package com.eza.spicyex.beautifullyrics.entities;

import android.graphics.Bitmap;
import android.view.View;

/**
 * Common contract for the lyrics ambient background layer, kept so the controller does not depend
 * on the concrete renderer. Only {@code AmbientArtworkBackgroundView} implements it today: the animated
 * background is AGSL-only, so devices below API 33 get no layer rather than a lesser stand-in.
 */
public interface AmbientBackgroundLayer {
    /** Installs a prepared module-owned texture on the UI thread. */
    void updateImage(Bitmap art);

    /**
     * Installs a prepared texture plus its compact variance profile (a few floats).
     * Default keeps backward compatibility for non-adaptive layers.
     */
    default void updateImage(Bitmap art, AmbientArtworkProfile profile) {
        updateImage(art);
    }

    /**
     * 0..1 live audio level for this instant; see {@code AudioReactiveController}. Safe to call
     * from any thread. Default is a no-op so a layer that does not react need not implement it.
     */
    default void setAudioLevel(float level0to1) {
    }

    /** 0..1 snare/clap envelope for this instant, next to the kick in setAudioLevel; UI thread. */
    default void setAudioAccent(float accent0to1) {
    }

    /** 0..1 loudness of what is playing right now (not just the kicks); UI thread. */
    default void setAudioEnergy(float loudness0to1) {
    }

    /** Stops frames and releases texture references. The layer can be reused. */
    void release();

    void pauseRendering();

    void resumeRendering();

    /** The layer as a View for add/visibility plumbing. */
    View asView();
}
