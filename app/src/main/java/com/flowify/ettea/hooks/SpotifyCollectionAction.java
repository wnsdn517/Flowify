package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.xposed.XpLog;

import java.util.List;
import java.util.function.Supplier;

final class SpotifyCollectionAction {
    static final class AdvertisedAction {
        final String id;
        final String label;

        AdvertisedAction(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    interface Session {
        String packageName();
        String trackUri();
        List<AdvertisedAction> advertisedActions();
        boolean dispatch(String actionId);
    }

    private SpotifyCollectionAction() {}

    static boolean enabled(String mode) {
        // Explicit icon values, not "anything but the default": the default is now Heart.
        return "Heart".equals(mode) || "Star".equals(mode);
    }

    static boolean dispatch(String mode, SpotifyTrack expected, Supplier<SpotifyTrack> currentTrack,
                            Supplier<Session> sessionSupplier) {
        if (!enabled(mode) || !isSong(expected)) return false;
        try {
            SpotifyTrack current = currentTrack.get();
            if (!matches(expected, current)) {
                XpLog.log(TAG + " like: track moved on (expected " + describe(expected)
                        + " current " + describe(current) + ")");
                return false;
            }
            Session session = sessionSupplier.get();
            if (session == null || !"com.spotify.music".equals(session.packageName())
                    || !expected.uri.equals(session.trackUri())) {
                XpLog.log(TAG + " like: no matching Spotify session (session="
                        + (session == null ? "null" : session.packageName() + " uri="
                        + session.trackUri()) + " expected " + expected.uri + ")");
                return false;
            }
            String actionId = collectionActionId(session.advertisedActions());
            if (actionId == null) {
                XpLog.log(TAG + " like: Spotify advertised no collection action; actions="
                        + describeActions(session.advertisedActions()));
                return false;
            }
            if (!matches(expected, currentTrack.get()) || !expected.uri.equals(session.trackUri())) {
                XpLog.log(TAG + " like: track changed during dispatch");
                return false;
            }
            boolean dispatched = session.dispatch(actionId);
            if (!dispatched) XpLog.log(TAG + " like: Spotify rejected dispatch of '" + actionId + "'");
            return dispatched;
        } catch (RuntimeException unavailable) {
            XpLog.log(TAG + " like: dispatch threw " + unavailable);
            return false;
        }
    }

    private static final String TAG = NativeSpicyLyricsHook.TAG;

    private static String describe(SpotifyTrack track) {
        if (track == null) return "null";
        return track.uri + (track.saved ? " saved" : " unsaved");
    }

    private static String describeActions(List<AdvertisedAction> actions) {
        if (actions == null || actions.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (AdvertisedAction action : actions) {
            if (action == null) continue;
            if (sb.length() > 1) sb.append(", ");
            sb.append(action.id).append("/").append(action.label);
        }
        return sb.append("]").toString();
    }

    // Spotify's MediaSession custom-action ids for the Liked Songs toggle. Current builds use
    // ADD_TO (not saved) / CHECK_FILL (saved); older ones ADD_TO_COLLECTION /
    // REMOVE_FROM_COLLECTION. Ids are locale-independent - the labels are translated
    // ("컬렉션에 추가하기"), so label matching alone fails outside English.
    private static final java.util.Set<String> COLLECTION_ACTION_IDS = new java.util.HashSet<>(
            java.util.Arrays.asList("ADD_TO", "CHECK_FILL",
                    "ADD_TO_COLLECTION", "REMOVE_FROM_COLLECTION"));

    static String collectionActionId(List<AdvertisedAction> actions) {
        if (actions == null) return null;
        for (AdvertisedAction action : actions) {
            if (action != null && action.id != null && COLLECTION_ACTION_IDS.contains(action.id)) {
                return action.id;
            }
        }
        for (AdvertisedAction action : actions) {
            if (action == null || action.id == null) continue;
            if (containsCollection(action.id)
                    || (action.label != null && containsCollection(action.label))) {
                return action.id;
            }
        }
        return null;
    }

    // Spotify's save-to-Liked-Songs custom action is usually named around "collection"
    // (ADD_TO_COLLECTION / REMOVE_FROM_COLLECTION), but some builds/devices advertise it under
    // a different id or label. These extra tokens are all unambiguously the like/save action
    // (never the ban/"dislike" action, which is why plain "like" is deliberately excluded).
    private static final String[] COLLECTION_TOKENS = {
            "collection", "liked", "heart", "favorite", "favourite"
    };

    private static boolean containsCollection(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String token : COLLECTION_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    static boolean isSong(SpotifyTrack track) {
        return track != null && track.uri != null && track.uri.startsWith("spotify:track:")
                && track.uri.length() > "spotify:track:".length();
    }

    private static boolean matches(SpotifyTrack expected, SpotifyTrack current) {
        return current != null && expected.uri.equals(current.uri) && expected.saved == current.saved;
    }
}
