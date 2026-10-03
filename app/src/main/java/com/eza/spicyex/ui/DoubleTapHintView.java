package com.eza.spicyex.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A gesture hint for trying the double-tap like: a dark glass pill with a finger mark that taps
 * twice - two quick ripples, then a pause - beside the line of text. It takes no touches; the
 * owner fades it out once the user has double-tapped.
 */
public final class DoubleTapHintView extends View {
    private static final long LOOP_MS = 1600L;

    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripple = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final String text;
    private final float density;
    private final ValueAnimator loop;
    private float t;

    public DoubleTapHintView(Context context, String text) {
        super(context);
        this.text = text;
        this.density = context.getResources().getDisplayMetrics().density;
        pill.setColor(0xB3181818);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(density);
        stroke.setColor(0x33FFFFFF);
        dotPaint.setColor(Color.WHITE);
        ripple.setStyle(Paint.Style.STROKE);
        ripple.setStrokeWidth(1.6f * density);
        ripple.setColor(Color.WHITE);
        textPaint.setColor(0xF2FFFFFF);
        textPaint.setTextSize(15f * density);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setClickable(false);
        setFocusable(false);
        setContentDescription(text);
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(LOOP_MS);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loop.start();
    }

    @Override protected void onDetachedFromWindow() {
        loop.cancel();
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float icon = 44f * density;
        float w = icon + textPaint.measureText(text) + 40f * density;
        int width = Math.round(Math.min(w, MeasureSpec.getSize(widthSpec)));
        setMeasuredDimension(width, Math.round(56f * density));
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight();
        rect.set(0f, 0f, getWidth(), h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, pill);
        rect.inset(density / 2f, density / 2f);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, stroke);

        float cx = 16f * density + 14f * density;
        float cy = h / 2f;
        // Two taps: a press dip and a ripple each, at 0.0 and 0.22 of the loop, then rest.
        float press = Math.max(tap(t, 0f), tap(t, 0.22f));
        canvas.drawCircle(cx, cy, (7f - 2f * press) * density, dotPaint);
        drawRipple(canvas, cx, cy, t, 0f);
        drawRipple(canvas, cx, cy, t, 0.22f);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, cx + 22f * density, baseline, textPaint);
    }

    /** 1 at the moment of a tap at {@code at}, falling off quickly. */
    private static float tap(float t, float at) {
        float d = (t - at) / 0.06f;
        return d < 0f || d > 1f ? 0f : 1f - d;
    }

    private void drawRipple(Canvas canvas, float cx, float cy, float t, float at) {
        float p = (t - at) / 0.4f;
        if (p <= 0f || p >= 1f) return;
        float eased = 1f - (1f - p) * (1f - p);
        ripple.setAlpha(Math.round(200 * (1f - p)));
        canvas.drawCircle(cx, cy, (7f + 12f * eased) * density, ripple);
    }
}
