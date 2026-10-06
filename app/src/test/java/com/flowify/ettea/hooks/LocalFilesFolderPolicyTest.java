package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LocalFilesFolderPolicyTest {
    @Test
    public void normalizesMediaStoreFolderPaths() {
        assertEquals("Music/Jazz",
                LocalFilesFolderPolicy.normalizeFolderPath("\\Music\\Jazz\\"));
        assertEquals("", LocalFilesFolderPolicy.normalizeFolderPath(null));
        assertEquals("Local files root", LocalFilesFolderPolicy.folderLabel(""));
    }

    @Test
    public void hiddenFolderIncludesItsNestedFoldersOnly() {
        Set<String> hidden = new HashSet<>(Collections.singletonList("Music/Jazz"));

        assertTrue(LocalFilesFolderPolicy.isHidden(hidden, "Music/Jazz"));
        assertTrue(LocalFilesFolderPolicy.isHidden(hidden, "Music/Jazz/Live"));
        assertFalse(LocalFilesFolderPolicy.isHidden(hidden, "Music/Jazz Fusion"));
        assertFalse(LocalFilesFolderPolicy.isHidden(hidden, "Music/Classical"));
        assertFalse(LocalFilesFolderPolicy.isHidden(Collections.emptySet(), "Music/Jazz"));
        assertTrue(LocalFilesFolderPolicy.isHidden(
                new HashSet<>(Collections.singletonList("")), ""));
    }

    @Test
    public void legacyDataPathUsesParentRelativeToStorageRoot() {
        assertEquals("Music/Jazz",
                LocalFilesFolderPolicy.parentFolderPath(
                        "/storage/emulated/0/Music/Jazz/song.mp3",
                        "/storage/emulated/0"));
        assertEquals("", LocalFilesFolderPolicy.parentFolderPath(
                "/storage/emulated/0/song.mp3", "/storage/emulated/0"));
        assertEquals("storage/ABCD/Music",
                LocalFilesFolderPolicy.parentFolderPath(
                        "/storage/ABCD/Music/song.mp3", "/storage/emulated/0"));
    }
}
