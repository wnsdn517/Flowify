package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ScrollMotionMigrationTest {
    private static StatusBarHiddenModeMigrationTest.FakePrefs prefs() {
        return new StatusBarHiddenModeMigrationTest.FakePrefs();
    }

    @Test
    public void slideOffBecomesStatic() {
        StatusBarHiddenModeMigrationTest.FakePrefs prefs = prefs();
        prefs.values.put(Settings.LINE_SLIDE_ANIMATION.key, false);
        SettingsStore.migrateScrollMotion(prefs);
        assertEquals("Static", prefs.values.get(Settings.SCROLL_MOTION.key));
    }

    @Test
    public void slideOnKeepsTheSpring() {
        StatusBarHiddenModeMigrationTest.FakePrefs prefs = prefs();
        prefs.values.put(Settings.LINE_SLIDE_ANIMATION.key, true);
        SettingsStore.migrateScrollMotion(prefs);
        assertEquals("Spring", prefs.values.get(Settings.SCROLL_MOTION.key));
    }

    @Test
    public void slideOnWithSpringTurnedDownBecomesSmooth() {
        StatusBarHiddenModeMigrationTest.FakePrefs prefs = prefs();
        prefs.values.put(Settings.LINE_SLIDE_ANIMATION.key, true);
        prefs.values.put(Settings.APPLE_SPRING_STRENGTH.key, 60);
        SettingsStore.migrateScrollMotion(prefs);
        assertEquals("Smooth", prefs.values.get(Settings.SCROLL_MOTION.key));
    }

    @Test
    public void springWithoutSlideUsesTheOldSlideDefaultOff() {
        StatusBarHiddenModeMigrationTest.FakePrefs prefs = prefs();
        prefs.values.put(Settings.APPLE_SPRING_STRENGTH.key, 150);
        SettingsStore.migrateScrollMotion(prefs);
        assertEquals("Static", prefs.values.get(Settings.SCROLL_MOTION.key));
    }

    @Test
    public void landscapeTwinMigratesSeparately() {
        StatusBarHiddenModeMigrationTest.FakePrefs prefs = prefs();
        prefs.values.put(Settings.LINE_SLIDE_ANIMATION.key, true);
        prefs.values.put(Settings.LINE_SLIDE_ANIMATION.key + "_ls", false);
        SettingsStore.migrateScrollMotion(prefs);
        assertEquals("Spring", prefs.values.get(Settings.SCROLL_MOTION.key));
        assertEquals("Static", prefs.values.get(Settings.SCROLL_MOTION.key + "_ls"));
    }

    @Test
    public void untouchedInstallsAndExistingChoicesAreLeftAlone() {
        StatusBarHiddenModeMigrationTest.FakePrefs fresh = prefs();
        SettingsStore.migrateScrollMotion(fresh);
        assertFalse(fresh.values.containsKey(Settings.SCROLL_MOTION.key));

        StatusBarHiddenModeMigrationTest.FakePrefs chosen = prefs();
        chosen.values.put(Settings.LINE_SLIDE_ANIMATION.key, false);
        chosen.values.put(Settings.SCROLL_MOTION.key, "Smooth");
        SettingsStore.migrateScrollMotion(chosen);
        assertEquals("Smooth", chosen.values.get(Settings.SCROLL_MOTION.key));
    }
}
