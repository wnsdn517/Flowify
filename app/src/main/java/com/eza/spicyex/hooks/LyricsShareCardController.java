package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.xposed.XpLog;

import java.io.OutputStream;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Renders an Apple Music-style share card (quoted lyric line or, with no lyrics available, a
 * plain track card) as a bitmap, shows it in a long-press "pop in" preview over the fullscreen
 * lyrics screen, and hands it to the system share sheet. Runs entirely inside the hooked Spotify
 * process, so sharing goes through {@link MediaStore} (any app's process can insert its own rows
 * there without special provider wiring) rather than a FileProvider, which would need a file
 * living under this module's own app-private storage - not reachable from here.
 */
final class LyricsShareCardController {
    private static final String TAG = "[SpotifyPlusLyricsShare]";
    private static final int CARD_WIDTH = 1080;
    private static final int CARD_HEIGHT = 1350;

    private final Activity activity;
    private View overlay;

    LyricsShareCardController(Activity activity) {
        this.activity = activity;
    }

    /** Long-pressed a real lyric line: quote it, with its translation underneath if present. */
    void showForLine(ViewGroup root, SpotifyTrack track, Bitmap art, String quote, String translation) {
        show(root, track, art, safe(quote), safe(translation));
    }

    /** No lyrics for this track at all: fall back to a plain "now playing" track card. */
    void showTrackCard(ViewGroup root, SpotifyTrack track, Bitmap art) {
        show(root, track, art, "", "");
    }

    private void show(ViewGroup root, SpotifyTrack track, Bitmap art, String quote, String translation) {
        if (activity == null || root == null || track == null) return;
        dismiss();
        Bitmap card;
        try {
            card = renderCard(art, quote, translation, safe(track.title), safe(track.artist));
        } catch (Throwable t) {
            XpLog.log(TAG + " render failed: " + t);
            return;
        }
        overlay = buildPreview(root, card, track, quote);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void dismiss() {
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
    }

    // --- Preview UI: dim scrim + a card that springs in like a long-press context menu ---

    private View buildPreview(ViewGroup root, Bitmap card, SpotifyTrack track, String quote) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.getBackground().setAlpha(0);
        scrim.setOnClickListener(v -> dismiss());

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClickable(true); // swallow taps so they don't fall through to the scrim

        ImageView cardView = new ImageView(activity);
        cardView.setImageBitmap(card);
        cardView.setAdjustViewBounds(true);
        int cardWidthPx = Math.round(root.getResources().getDisplayMetrics().widthPixels * 0.78f);
        column.addView(cardView, new LinearLayout.LayoutParams(cardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView shareButton = pillButton("Share", true);
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareLp.topMargin = dp(20);
        shareButton.setOnClickListener(v -> {
            shareCard(card, track, quote);
            dismiss();
        });
        column.addView(shareButton, shareLp);

        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(column, columnLp);

        column.setScaleX(0.55f);
        column.setScaleY(0.55f);
        column.setAlpha(0f);
        scrim.getBackground().setAlpha(0);
        scrim.post(() -> {
            fadeScrimIn(scrim);
            column.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(340)
                    .setInterpolator(new OvershootInterpolator(1.05f))
                    .start();
        });
        return scrim;
    }

    private void fadeScrimIn(FrameLayout scrim) {
        android.animation.ValueAnimator fader = android.animation.ValueAnimator.ofInt(0, 165);
        fader.setDuration(220);
        fader.addUpdateListener(a -> scrim.getBackground().setAlpha((int) a.getAnimatedValue()));
        fader.start();
    }

    private TextView pillButton(String text, boolean primary) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(28), dp(12), dp(28), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(primary ? Color.rgb(30, 215, 96) : Color.argb(60, 255, 255, 255));
        bg.setCornerRadius(dp(24));
        button.setBackground(bg);
        if (primary) button.setTextColor(Color.BLACK);
        return button;
    }

    // --- Sharing ---

    private void shareCard(Bitmap card, SpotifyTrack track, String quote) {
        new Thread(() -> {
            try {
                // Construct text message combining the optional active lyric quote 
                // alongside the Spotify track link to feed directly into the share intent.
                String text = isBlank(quote)
                        ? safe(track.title) + " - " + safe(track.artist) + " " + safe(track.uri)
                        : "\"" + quote + "\" \n" + safe(track.title) + " - " + safe(track.artist) + " \n" + safe(track.uri);

                // Trigger official Spotify internal share/routing flow by targeting the specific
                // package and action context of the client app, letting Spotify own the sheet.
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.setPackage("com.spotify.music");
                send.putExtra(Intent.EXTRA_TEXT, text);
                send.putExtra(Intent.EXTRA_SUBJECT, "Share lyric");
                send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                activity.runOnUiThread(() -> {
                    try {
                        activity.startActivity(send);
                    } catch (Throwable ignored) {
                        // Fallback: if explicit internal packaging fails, launch standard picker
                        // with identical metadata text constraints for safe degraded delivery.
                        Intent chooser = Intent.createChooser(send, "Share lyric");
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(chooser);
                    }
                });
            } catch (Throwable t) {
                XpLog.log(TAG + " share failed: " + t);
            }
        }).start();
    }

    /**
     * Any process's UID can insert its own rows into MediaStore - unlike a FileProvider, which
     * would need the backing file under this module's own (unreachable, from inside the host
     * process) private storage. The inserted row is immediately shareable via its content Uri.
     */
    private Uri saveToGallery(Bitmap card, SpotifyTrack track) {
        Context context = activity.getApplicationContext();
        String name = "spicyex_lyric_" + System.currentTimeMillis() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpicyEx");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri item = context.getContentResolver().insert(collection, values);
        if (item == null) return null;
        try (OutputStream out = context.getContentResolver().openOutputStream(item)) {
            if (out == null) return null;
            card.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable t) {
            XpLog.log(TAG + " write failed: " + t);
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(item, values, null, null);
        }
        return item;
    }

    // --- Card rendering ---

    private Bitmap renderCard(Bitmap art, String quote, String translation, String title, String artist) {
        Bitmap bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int[] gradient = extractGradient(art);

        Path clip = new Path();
        RectF bounds = new RectF(0, 0, CARD_WIDTH, CARD_HEIGHT);
        clip.addRoundRect(bounds, dp(28), dp(28), Path.Direction.CW);
        canvas.clipPath(clip);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, CARD_WIDTH, CARD_HEIGHT,
                gradient[0], gradient[1], Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, bg);

        boolean isQuote = !isBlank(quote);
        if (isQuote) {
            drawQuoteGlyph(canvas);
            drawQuoteText(canvas, quote, translation);
        } else if (art != null) {
            drawCenteredArt(canvas, art);
        }

        drawFooter(canvas, art, title, artist, isQuote);
        return bitmap;
    }

    private void drawQuoteGlyph(Canvas canvas) {
        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(Color.argb(70, 255, 255, 255));
        mark.setTextSize(dp(160));
        mark.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        canvas.drawText("“", dp(48), dp(190), mark);
    }

    private void drawQuoteText(Canvas canvas, String quote, String translation) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        int width = CARD_WIDTH - dp(48) * 2;
        int size = dp(64);
        StaticLayout layout;
        while (true) {
            paint.setTextSize(size);
            layout = staticLayout(quote, paint, width);
            if (layout.getLineCount() <= 6 || size <= dp(28)) break;
            size -= dp(4);
        }
        canvas.save();
        canvas.translate(dp(48), dp(260));
        layout.draw(canvas);
        float usedHeight = layout.getHeight();
        canvas.restore();

        if (!isBlank(translation)) {
            TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            sub.setColor(Color.argb(190, 255, 255, 255));
            sub.setTextSize(dp(30));
            StaticLayout subLayout = staticLayout(translation, sub, width);
            canvas.save();
            canvas.translate(dp(48), dp(260) + usedHeight + dp(20));
            subLayout.draw(canvas);
            canvas.restore();
        }
    }

    private void drawCenteredArt(Canvas canvas, Bitmap art) {
        int size = dp(420);
        int left = (CARD_WIDTH - size) / 2;
        int top = dp(280);
        RectF frame = new RectF(left, top, left + size, top + size);
        Path clip = new Path();
        clip.addRoundRect(frame, dp(20), dp(20), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmap(art, null, frame, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        canvas.restore();
    }

    private void drawFooter(Canvas canvas, Bitmap art, String title, String artist, boolean compact) {
        int footerTop = CARD_HEIGHT - dp(190);
        int thumbSize = dp(96);
        int left = dp(48);
        if (art != null) {
            RectF frame = new RectF(left, footerTop, left + thumbSize, footerTop + thumbSize);
            Path clip = new Path();
            clip.addRoundRect(frame, dp(12), dp(12), Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(art, null, frame, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            canvas.restore();
        }
        int textLeft = art != null ? left + thumbSize + dp(20) : left;
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setTextSize(dp(34));
        canvas.drawText(ellipsize(title, titlePaint, CARD_WIDTH - textLeft - dp(48)),
                textLeft, footerTop + dp(42), titlePaint);

        TextPaint artistPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        artistPaint.setColor(Color.argb(190, 255, 255, 255));
        artistPaint.setTextSize(dp(28));
        canvas.drawText(ellipsize(artist, artistPaint, CARD_WIDTH - textLeft - dp(48)),
                textLeft, footerTop + dp(84), artistPaint);

        TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(Color.argb(140, 255, 255, 255));
        mark.setTextSize(dp(24));
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("SpicyEx", left, CARD_HEIGHT - dp(36), mark);
    }

    private static String ellipsize(String text, TextPaint paint, int maxWidth) {
        String safeText = safe(text);
        return android.text.TextUtils.ellipsize(safeText, paint, maxWidth, android.text.TextUtils.TruncateAt.END).toString();
    }

    private static StaticLayout staticLayout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.08f)
                .build();
    }

    /**
     * No {@code androidx.palette} dependency: downsamples the artwork and averages its pixels,
     * then pushes that average toward a vivid top color and a near-black bottom color so the
     * gradient reads the same way Apple Music/Spotify share-card backgrounds do.
     */
    private static int[] extractGradient(Bitmap art) {
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) {
            return new int[]{Color.rgb(60, 60, 66), Color.rgb(14, 14, 16)};
        }
        Bitmap small = Bitmap.createScaledBitmap(art, 20, 20, true);
        long r = 0, g = 0, b = 0;
        int count = small.getWidth() * small.getHeight();
        int[] pixels = new int[count];
        small.getPixels(pixels, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
        for (int pixel : pixels) {
            r += Color.red(pixel);
            g += Color.green(pixel);
            b += Color.blue(pixel);
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / count), (int) (g / count), (int) (b / count), hsv);

        float[] top = hsv.clone();
        top[1] = Math.min(1f, top[1] * 1.3f + 0.1f);
        top[2] = Math.max(top[2], 0.5f);
        float[] bottom = hsv.clone();
        bottom[1] = Math.min(1f, bottom[1]);
        bottom[2] = Math.min(bottom[2] * 0.28f, 0.16f);
        return new int[]{Color.HSVToColor(top), Color.HSVToColor(bottom)};
    }

    private int dp(int value) {
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
