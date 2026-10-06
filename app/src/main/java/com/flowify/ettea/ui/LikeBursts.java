package com.flowify.ettea.ui;

import android.content.Context;

/**
 * The double-tap like styles (Settings.DOUBLE_TAP_LIKE_EFFECT). Each was designed separately and
 * is kept whole in its own class; the stored values below are the setting's options.
 */
public final class LikeBursts {
    private LikeBursts() {
    }

    /** Setting values, in the order the setting lists them. */
    public static final String[] STYLES = {
            "Aurora", "Glow", "Pulse", "Watercolor", "Radiant", "Stardust", "Gravity", "Crystal",
            "Liquid", "Lens", "Pearl", "Prism", "Bloom", "Classic"
    };

    /**
     * @param star  a star instead of a heart
     * @param big   the double-tap form; false is the small form around the like button
     * @param x     centre, in the parent's coordinates
     * @param size  the mark's nominal size in px
     */
    public static LikeBurst create(String style, Context context, boolean star, boolean big,
                                   float x, float y, float size) {
        String s = style == null ? "" : style;
        switch (s) {
            case "Glow": return new LikeBurstGlow(context, star, big, x, y, size);
            case "Pulse": return new LikeBurstPulse(context, star, big, x, y, size);
            case "Watercolor": return new LikeBurstWatercolor(context, star, big, x, y, size);
            case "Radiant": return new LikeBurstRadiant(context, star, big, x, y, size);
            case "Stardust": return new LikeBurstStardust(context, star, big, x, y, size);
            case "Gravity": return new LikeBurstGravity(context, star, big, x, y, size);
            case "Crystal": return new LikeBurstCrystal(context, star, big, x, y, size);
            case "Liquid": return new LikeBurstLiquid(context, star, big, x, y, size);
            case "Lens": return new LikeBurstLens(context, star, big, x, y, size);
            case "Pearl": return new LikeBurstPearl(context, star, big, x, y, size);
            case "Prism": return new LikeBurstPrism(context, star, big, x, y, size);
            case "Bloom": return new LikeBurstBloom(context, star, big, x, y, size);
            case "Classic": return new LikeBurstClassic(context, star, big, x, y, size);
            default: return new LikeBurstAurora(context, star, big, x, y, size);
        }
    }
}
