package com.eza.spicyex.hooks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;

/**
 * Static preview content for the layout editor's Demo toggle - a synthetic track, artwork, and
 * lyrics document so the editor has something to show when nothing (or nothing with useful
 * lyrics) is actually playing. Text is native per-script content only (no romanization/furigana
 * baked in): {@code NativeSpicyShellViewImpl#renderDocument()} already runs the real local
 * romanization pipeline against whatever it's handed, so the user's live reading-mode settings
 * annotate this exactly like a real document would.
 */
final class DemoLyricsContent {
    private DemoLyricsContent() {
    }

    /** Sentinel URI/imageId: never matches a real {@code SpotifyArtworkCache} entry. */
    static SpotifyTrack demoTrack() {
        return new SpotifyTrack("Layout Preview", "SpicyEx", "Demo",
                "spotify:track:spicyexlayoutpreviewdemo00", 45_000L, "#1ED760",
                SystemClock.elapsedRealtime(), "spotify:image:spicyexlayoutpreviewdemo00",
                180_000L, false);
    }

    /** Mixes short and long lines across every script this app has dedicated reading/romanization
     *  logic for (Japanese, Chinese, Korean, Cyrillic, Greek) plus one RTL line (Arabic), timed so
     *  {@link com.eza.spicyex.lyrics.LyricTimeline#applySyncedRows} synthesizes intro/mid-song/
     *  outro gap rows purely from the timing, matching a real synced document's shape. */
    static LyricsDocument demoDocument() {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "spicyex-layout-preview-demo";
        doc.provider = "Demo";
        doc.fetchSource = "demo";
        doc.type = "Line";
        doc.language = "en";
        doc.durationMs = 50_000L;
        addLine(doc, 4_000, 7_000, "Hello");
        addLine(doc, 7_000, 10_000, "今日はいい天気ですね");
        addLine(doc, 10_000, 13_500, "你好，欢迎来到这里");
        addLine(doc, 13_500, 17_000, "오늘은 정말 좋은 날이에요");
        addLine(doc, 17_000, 20_500, "Сегодня прекрасный день");
        addLine(doc, 20_500, 24_000, "Σήμερα είναι μια όμορφη μέρα");
        addLine(doc, 24_000, 27_500, "اليوم يوم جميل");
        addLine(doc, 27_500, 33_000,
                "This is a longer preview line meant to wrap across more than one row so you "
                        + "can see how longer lyrics behave in this layout.");
        addLine(doc, 33_000, 36_000, "Yeah");
        addLine(doc, 40_000, 43_000, "One more line to check spacing");
        addLine(doc, 43_000, 46_000, "Thanks!");
        return doc;
    }

    private static void addLine(LyricsDocument doc, long startMs, long endMs, String text) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.startMs = startMs;
        line.endMs = endMs;
        doc.lines.add(line);
    }

    /** Drawn at runtime - the app ships no placeholder album-art asset. A plain diagonal
     *  gradient plus a faint note glyph, large enough to downscale cleanly to any real art
     *  frame's size via the existing {@code roundBitmap} rounding helper. */
    static Bitmap demoArtBitmap() {
        int size = 512;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gradientPaint.setShader(new LinearGradient(0, 0, size, size,
                Color.rgb(64, 47, 120), Color.rgb(20, 130, 110), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, size, size, gradientPaint);
        Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyphPaint.setColor(0x33FFFFFF);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTextSize(size * 0.55f);
        Paint.FontMetrics fm = glyphPaint.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("♪", size / 2f, baseline, glyphPaint);
        return bitmap;
    }
}
