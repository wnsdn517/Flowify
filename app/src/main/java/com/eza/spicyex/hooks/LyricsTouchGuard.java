package com.eza.spicyex.hooks;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.MotionEvent;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

/**
 * Accidental-touch guard for the lyrics screen ({@link Settings#ACCIDENTAL_TOUCH_GUARD}). The
 * lyrics screen is meant to stay on while music plays, which is exactly when a phone goes into a
 * pocket or gets gripped one-handed - and every surface on it acts on a tap (seek to a line,
 * like, skip, play/pause on the cover). Two checks, the same ones phone makers' own "pocket
 * mode" / "accidental touch protection" build on:
 *
 * <ul>
 *   <li><b>Pocket guard</b>: while the proximity sensor reads "near" (the screen is against
 *   fabric, a bag, a face), every touch is dropped. It only engages on a far-to-near change seen
 *   while the screen is open, so a sensor that is stuck or permanently shadowed by a case never
 *   locks the screen out.</li>
 *   <li><b>Palm rejection</b>: a gesture whose contact patch is far larger than a fingertip - a
 *   palm or the side of a hand wrapping the edge - is dropped, and one that turns into a palm
 *   midway is cancelled so nothing it already started (a press, a scroll) acts on release. The
 *   platform's own palm verdict ({@code FLAG_CANCELED}) is honoured too.</li>
 * </ul>
 *
 * A dropped gesture stays dropped until the next finger-down, so the tail of a pocket touch
 * cannot leak through the moment the sensor clears.
 */
final class LyricsTouchGuard implements SensorEventListener {
    enum Verdict { PASS, DROP, CANCEL }

    /** A fingertip reports roughly 6-12mm; a palm or hand edge well over twice that. */
    private static final float PALM_TOUCH_MM = 22f;
    /** Upper bound on what counts as "near", for sensors that report a range instead of 0/max. */
    private static final float NEAR_CM = 3f;

    private final SpotifyPlusConfig config;
    private final SensorManager sensors;
    private final Sensor proximity;
    private final float palmPx;
    private boolean listening;
    /** The last reading, and whether a far reading has been seen since the screen opened. */
    private boolean near;
    private boolean sawFar;
    private boolean pocketed;
    private boolean gestureBlocked;

    LyricsTouchGuard(Activity activity, SpotifyPlusConfig config) {
        this.config = config;
        SensorManager manager = null;
        Sensor sensor = null;
        try {
            manager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
            if (manager != null) sensor = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        } catch (Throwable ignored) {
        }
        this.sensors = manager;
        this.proximity = sensor;
        float xdpi = activity.getResources().getDisplayMetrics().xdpi;
        if (xdpi <= 0f) xdpi = 160f * activity.getResources().getDisplayMetrics().density;
        this.palmPx = PALM_TOUCH_MM / 25.4f * xdpi;
    }

    private boolean enabled() {
        try {
            return Boolean.TRUE.equals(config.get(Settings.ACCIDENTAL_TOUCH_GUARD));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Starts or stops listening to match the setting (call on start and on preference change). */
    void refresh(boolean running) {
        boolean want = running && enabled() && proximity != null && sensors != null;
        if (want == listening) return;
        if (want) {
            near = false;
            sawFar = false;
            pocketed = false;
            try {
                listening = sensors.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
            } catch (Throwable ignored) {
                listening = false;
            }
        } else {
            try {
                sensors.unregisterListener(this);
            } catch (Throwable ignored) {
            }
            listening = false;
            pocketed = false;
        }
    }

    void stop() {
        refresh(false);
        gestureBlocked = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length == 0) return;
        float max = proximity == null ? NEAR_CM : proximity.getMaximumRange();
        float value = event.values[0];
        boolean nowNear = value < Math.min(NEAR_CM, max);
        if (!nowNear) sawFar = true;
        near = nowNear;
        // A sensor that has read "near" since the screen opened is stuck or shadowed by a case,
        // not a pocket - only a far-to-near change counts.
        pocketed = near && sawFar;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Decides what the shell does with a touch event before dispatching it. */
    Verdict check(MotionEvent ev) {
        if (ev == null) return Verdict.PASS;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            gestureBlocked = false;
            if (!enabled()) return Verdict.PASS;
            if (pocketed || isPalm(ev)) {
                gestureBlocked = true;
                return Verdict.DROP;
            }
            return Verdict.PASS;
        }
        if (gestureBlocked) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gestureBlocked = false;
            }
            return Verdict.DROP;
        }
        if (!enabled()) return Verdict.PASS;
        boolean ending = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (action != MotionEvent.ACTION_CANCEL && (pocketed || isPalm(ev) || platformCanceled(ev))) {
            // Already delivered a down: cancel it, so a press or scroll in progress never fires.
            gestureBlocked = !ending;
            return Verdict.CANCEL;
        }
        return Verdict.PASS;
    }

    private boolean isPalm(MotionEvent ev) {
        for (int i = 0; i < ev.getPointerCount(); i++) {
            if (ev.getTouchMajor(i) >= palmPx) return true;
        }
        return false;
    }

    /** Android 13+ marks an up the system itself judged to be an accidental palm touch. */
    private static boolean platformCanceled(MotionEvent ev) {
        if (Build.VERSION.SDK_INT < 33) return false;
        int action = ev.getActionMasked();
        return (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && (ev.getFlags() & MotionEvent.FLAG_CANCELED) != 0;
    }
}
