package com.eza.spicyex.hooks;

import android.content.Context;
import android.os.SystemClock;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

import java.lang.ref.WeakReference;

public final class ConnectMirror {
    private static final String ENTRY = "com.eza.spicyex.spotifyconnect.ConnectEntry";
    private static final long PUSH_MIN_INTERVAL_MS = 3000;

    private static volatile WeakReference<Context> appRef = new WeakReference<>(null);
    private static volatile long lastPushElapsed;
    private static volatile boolean lastPushedPlaying;
    private static volatile boolean installed;
    private static volatile java.lang.reflect.Method phoneStateMethod;
    private static volatile java.lang.reflect.Method remoteCommandMethod;
    private static volatile boolean entryMissing;

    private ConnectMirror() {
    }

    public static void init(Context context) {
        try {
            Context app = context.getApplicationContext();
            appRef = new WeakReference<>(app != null ? app : context);
        } catch (Throwable ignored) {
        }
    }

    public static void install(ClassLoader classLoader) {
        if (installed) return;
        installed = true;
        try {
            Class<?> builder;
            try {
                builder = XpReflect.findClass("okhttp3.Request$Builder", classLoader);
            } catch (Throwable t) {
                return;
            }
            XpHooks.hookAllMethods(builder, "build", "connect:RequestBuilder#build",
                    (XpHooks.After) param -> {
                        try {
                            classifyAndForward(param.getResult());
                        } catch (Throwable ignored) {
                        }
                    });
            
            // Disabled ad-blocker via build hook to avoid interference with token generation completely.
            // Spotify handles ads at different dynamic layers, network intercepts or broad method hooks 
            // can break internal token parsing frameworks if not handled separately.
            /*
            XpHooks.hookAllMethods(builder, "build", "ads:RequestBuilder#build",
                    (XpHooks.Before) param -> {
                        ...
                    });
            */
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " connect mirror install failed: " + t.getClass().getName());
        }
    }

    public static void pushPhoneState(boolean playing, long positionMs) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (playing == lastPushedPlaying && now - lastPushElapsed < PUSH_MIN_INTERVAL_MS) return;
            lastPushedPlaying = playing;
            lastPushElapsed = now;
            if (entryMissing) return;
            Context context = appRef.get();
            if (context == null) return;
            java.lang.reflect.Method m = phoneStateMethod;
            if (m == null) {
                try {
                    m = Class.forName(ENTRY).getMethod("onPhoneState", Context.class, boolean.class, long.class);
                    phoneStateMethod = m;
                } catch (ClassNotFoundException e) {
                    entryMissing = true;
                    return;
                }
            }
            m.invoke(null, context, playing, positionMs);
        } catch (Throwable ignored) {
        }
    }



    private static void classifyAndForward(Object request) {
        if (request == null) return;
        String method = "";
        String url = "";
        try {
            Object rawMethod = XpReflect.callMethod(request, "method");
            if (rawMethod != null) method = rawMethod.toString();
        } catch (Throwable ignored) {}
        try {
            Object rawUrl = XpReflect.callMethod(request, "url");
            if (rawUrl != null) url = rawUrl.toString();
        } catch (Throwable ignored) {}
        if (url.isEmpty()) return;
        if (url.indexOf("player") < 0 && url.indexOf("transfer") < 0
                && url.indexOf("command") < 0 && url.indexOf("Player") < 0) return;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        String kind = null;
        if (lower.contains("transfer")) {
            kind = "transfer";
        } else if (lower.contains("/me/player")) {
            kind = (method.equalsIgnoreCase("PUT") || method.equalsIgnoreCase("POST")) ? "transfer" : "player";
        } else if (lower.contains("spclient") && lower.contains("player")) {
            kind = "player";
        } else if (lower.contains("command")) {
            kind = "command";
        }
        if (kind == null) return;
        try {
            if (entryMissing) return;
            Context context = appRef.get();
            if (context == null) return;
            java.lang.reflect.Method m = remoteCommandMethod;
            if (m == null) {
                try {
                    m = Class.forName(ENTRY).getMethod("onRemoteCommand", Context.class, String.class);
                    remoteCommandMethod = m;
                } catch (ClassNotFoundException e) {
                    entryMissing = true;
                    return;
                }
            }
            m.invoke(null, context, kind);
        } catch (Throwable ignored) {
        }
    }
}
