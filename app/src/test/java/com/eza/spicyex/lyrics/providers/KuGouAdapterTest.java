package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Base64;
import java.util.List;

/** Ported from BitChord's KuGou source (github.com/kushagrasinghx/BitChord), covering the pure
 *  response-parsing and credit-stripping logic with fixtures shaped like its real responses. */
public class KuGouAdapterTest {

    @Test
    public void closestHashesFirstOrdersByDurationDeltaAndDropsOutOfTolerance() {
        String json = "{\"data\":{\"info\":["
                + "{\"hash\":\"far\",\"duration\":100},"
                + "{\"hash\":\"close\",\"duration\":205},"
                + "{\"hash\":\"exact\",\"duration\":204},"
                + "{\"hash\":\"tooFar\",\"duration\":9999}"
                + "]}}";
        List<String> hashes = KuGouAdapter.closestHashesFirst(json, 204);
        assertEquals(2, hashes.size());
        assertEquals("exact", hashes.get(0));
        assertEquals("close", hashes.get(1));
    }

    @Test
    public void closestHashesFirstIgnoresDurationWhenTrackDurationUnknown() {
        String json = "{\"data\":{\"info\":[{\"hash\":\"a\",\"duration\":9999},{\"hash\":\"b\",\"duration\":1}]}}";
        List<String> hashes = KuGouAdapter.closestHashesFirst(json, -1);
        assertEquals(2, hashes.size());
    }

    @Test
    public void closestHashesFirstReturnsEmptyOnMalformedJson() {
        assertTrue(KuGouAdapter.closestHashesFirst("not json", 200).isEmpty());
        assertTrue(KuGouAdapter.closestHashesFirst("{}", 200).isEmpty());
    }

    @Test
    public void firstCandidateReadsIdAndAccessKey() {
        String json = "{\"candidates\":[{\"id\":\"207065411\",\"accesskey\":\"E0878\"},"
                + "{\"id\":\"ignored\",\"accesskey\":\"ignored\"}]}";
        KuGouAdapter.Candidate candidate = KuGouAdapter.firstCandidate(json);
        assertEquals("207065411", candidate.id);
        assertEquals("E0878", candidate.accessKey);
    }

    @Test
    public void firstCandidateIsNullWhenNoCandidates() {
        assertNull(KuGouAdapter.firstCandidate("{\"candidates\":[]}"));
        assertNull(KuGouAdapter.firstCandidate("not json"));
    }

    @Test
    public void decodeLrcBase64DecodesAndStripsUtf8Bom() {
        String lrcWithBom = "﻿[00:00.00]Believer";
        String b64 = Base64.getEncoder().encodeToString(lrcWithBom.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String json = "{\"content\":\"" + b64 + "\"}";
        String decoded = KuGouAdapter.decodeLrc(json);
        assertEquals("[00:00.00]Believer", decoded);
    }

    @Test
    public void decodeLrcIsNullOnMalformedPayload() {
        assertNull(KuGouAdapter.decodeLrc("{\"content\":\"not-base64!!\"}"));
        assertNull(KuGouAdapter.decodeLrc("not json"));
    }

    @Test
    public void stripCreditsRemovesHeadAndTailCreditLinesOnly() {
        String raw = String.join("\n",
                "[00:00.00]Imagine Dragons - Believer",
                "[00:00.62]Written by: Dan Reynolds",
                "[00:03.75]Composed by: Dan Reynolds",
                "[00:10.00]First things first",
                "[00:14.00]I'ma say all the words inside my head",
                "[03:40.00]The master of my sea");
        String cleaned = KuGouAdapter.stripCredits(raw);
        assertTrue(cleaned.contains("First things first"));
        assertTrue(cleaned.contains("The master of my sea"));
        assertTrue(!cleaned.contains("Written by"));
        assertTrue(!cleaned.contains("Composed by"));
        assertTrue(!cleaned.contains("Imagine Dragons - Believer"));
    }

    @Test
    public void stripCreditsLeavesAColonLyricAloneWhenItIsNotNearEitherEdge() {
        // The 30-line credit-scan window only looks at either edge, by design (see
        // KuGouAdapter#stripCredits) - a colon-shaped line deep in the middle must survive.
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 40; i++) raw.append("[00:").append(String.format("%02d", i)).append(".00]Filler line ").append(i).append('\n');
        raw.append("[00:41.00]Time: it waits for no one\n");
        for (int i = 42; i < 80; i++) raw.append("[00:").append(i).append(".00]Filler line ").append(i).append('\n');
        String cleaned = KuGouAdapter.stripCredits(raw.toString());
        assertTrue(cleaned.contains("Time: it waits for no one"));
    }

    @Test
    public void stripCreditsOnEmptyInputIsEmpty() {
        assertEquals("", KuGouAdapter.stripCredits(""));
        assertEquals("", KuGouAdapter.stripCredits("no timestamps here"));
    }
}
