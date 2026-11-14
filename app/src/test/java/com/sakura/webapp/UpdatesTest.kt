package com.sakura.webapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version order across the old four-part numbers and the 1.0 betas. */
class UpdatesTest {
    @Test
    fun betasAreNewerThanOldVersions() {
        assertTrue(Updates.isNewer("v1.0-Beta-001", "0.9.1.7"))
        assertFalse(Updates.isNewer("v0.9.1.7", "1.0 Beta 001"))
    }

    @Test
    fun betasGoUpByTheirNumber() {
        assertTrue(Updates.isNewer("v1.0-Beta-002", "1.0 Beta 001"))
        assertFalse(Updates.isNewer("v1.0-Beta-001", "1.0 Beta 001"))
        assertFalse(Updates.isNewer("v1.0-Beta-001", "1.0 Beta 002"))
        assertTrue(Updates.isNewer("1.0 Beta 010", "1.0 Beta 009"))
    }

    @Test
    fun theFinalVersionIsNewerThanItsBetas() {
        assertTrue(Updates.isNewer("v1.0", "1.0 Beta 042"))
        assertFalse(Updates.isNewer("v1.0-Beta-042", "1.0"))
        assertTrue(Updates.isNewer("v1.0.1", "1.0"))
    }

    @Test
    fun oldNumbersStillCompare() {
        assertTrue(Updates.isNewer("v0.10.0", "0.9.3"))
        assertFalse(Updates.isNewer("v0.9.1.6", "0.9.1.7"))
    }
}
