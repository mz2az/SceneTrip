package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.FootprintRecording
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FootprintPrivacyTests {
    @Test fun consentIsRequiredAndShowingTheMapCannotStartRecording() {
        val store = FootprintRecording()
        store.record(37.5663, 126.9779, 1)
        assertTrue(store.state.points.isEmpty())
        assertFalse(store.state.recording)
        store.consent(true)
        store.record(37.5663, 126.9779, 1)
        store.record(37.5700, 126.9830, 2)
        assertEquals(2, store.state.points.size)
    }

    @Test fun forgettingTheAccountClearsPointsAndConsentAndBlocksLaterSamples() {
        val store = FootprintRecording(recording = true)
        store.record(37.5663, 126.9779, 1)
        store.forgetOwner()
        store.record(37.5700, 126.9830, 2)
        assertTrue(store.state.points.isEmpty())
        assertFalse(store.state.recording)
    }

    @Test fun recordsOnlyFiniteKoreanLocationsAtLeast25MetersApart() {
        val store = FootprintRecording(recording = true)
        store.record(37.5663, 126.9779, 1)
        store.record(37.56631, 126.97791, 2)
        store.record(35.0, 139.0, 3)
        store.record(Double.NaN, 127.0, 4)
        assertEquals(1, store.state.points.size)
    }
}
