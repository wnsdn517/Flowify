package com.flowify.ettea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.flowify.ettea.lyrics.language.LanguageModelPack;

import org.junit.Test;

public class LanguageModelPackStateTest {
    @Test
    public void transientStatusDefaultsToIdle() {
        LanguageModelPack.clearTransientState();
        LanguageModelPack.DownloadStatus status = LanguageModelPack.status();
        assertNotNull(status);
        assertEquals(LanguageModelPack.Phase.IDLE, status.phase);
        assertEquals(0, status.progressPercent);
        assertEquals("", status.errorCode);
    }
}
