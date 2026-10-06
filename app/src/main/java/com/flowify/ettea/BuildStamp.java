package com.eza.spicyex;

/**
 * The fork's version as shown and reported: {@code nightly 2.0.0 (versionCode) [C172B918_20261004]} -
 * MAJOR.MINOR.PATCH, the version code, then this fork's commit count (C), the upstream build it
 * is based on (B) and the build date. All of it comes from the build (app/build.gradle).
 */
public final class BuildStamp {
    public static final String VERSION = BuildConfig.FORK_VERSION;
    public static final String NAME = BuildConfig.VERSION_NAME;
    public static final int VERSION_CODE = BuildConfig.FORK_VERSION_CODE;
    public static final String CLUE = BuildConfig.BUILD_TAG;
    public static final String FULL = VERSION + " (" + VERSION_CODE + ") [" + CLUE + "]";

    private BuildStamp() {
    }
}
