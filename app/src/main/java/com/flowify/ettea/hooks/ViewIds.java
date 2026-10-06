package com.eza.spicyex.hooks;

import android.view.View;

/**
 * Resource names of Spotify's views, without asking the framework about ids that are not
 * resources. Compose and some libraries give views generated ids (e.g. 0xecfe121d) in no
 * resource package; getResourceEntryName on those throws - which callers caught - but the
 * native AssetManager also logs "No package ID .. found for resource ID" every time, and the
 * scans that did it run on a timer.
 */
final class ViewIds {
    private ViewIds() {
    }

    /** True for ids in the app's own (0x7f) or the framework's (0x01) resource package. */
    static boolean isResourceId(int id) {
        int pkg = id >>> 24;
        return id != View.NO_ID && (pkg == 0x7f || pkg == 0x01);
    }

    /** The view id's resource entry name, or "" when it has none. */
    static String entryName(View view) {
        if (view == null || !isResourceId(view.getId())) return "";
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The view under root whose id is the app's resource {@code entryName}, or null. */
    static View findByEntry(View root, String entryName) {
        if (root == null || entryName == null || entryName.isEmpty()) return null;
        try {
            int id = root.getResources().getIdentifier(entryName, "id", root.getContext().getPackageName());
            return id == 0 ? null : root.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
