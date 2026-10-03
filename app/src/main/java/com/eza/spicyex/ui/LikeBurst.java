package com.eza.spicyex.ui;

import android.view.View;
import android.view.ViewGroup;

/** One double-tap like acknowledgement (see LikeBursts for the styles). */
public interface LikeBurst {
    /**
     * Adds the burst over {@code parent} and plays it; it removes itself at the end.
     *
     * @param backdrop the view behind the lyrics, for styles that answer through it, or null
     * @param lyrics   the view holding the lyrics, for styles that answer through it, or null
     */
    void play(ViewGroup parent, View backdrop, View lyrics);
}
