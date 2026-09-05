package com.fourseveneightnine.tv.ui

import com.fourseveneightnine.tv.ui.settings.TVSettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TVLibrarySurfacePolicyTest {
    @Test
    fun `library owns a ready idle home but never settings or playback`() {
        assertTrue(TVLibrarySurfacePolicy.shouldShow(startupReady = true, homePhase = true, settingsVisible = false, settingsConfigured = true))
        assertFalse(TVLibrarySurfacePolicy.shouldShow(startupReady = true, homePhase = true, settingsVisible = false, settingsConfigured = false))
        assertFalse(TVLibrarySurfacePolicy.shouldShow(startupReady = false, homePhase = true, settingsVisible = false, settingsConfigured = true))
        assertFalse(TVLibrarySurfacePolicy.shouldShow(startupReady = true, homePhase = false, settingsVisible = false, settingsConfigured = true))
        assertFalse(TVLibrarySurfacePolicy.shouldShow(startupReady = true, homePhase = true, settingsVisible = true, settingsConfigured = true))
    }

    @Test
    fun `unconfigured ready home always routes to Pair and Sync instead of the legacy overlay`() {
        assertTrue(TVLibrarySurfacePolicy.shouldShowPairing(
            startupReady = true,
            homePhase = true,
            settingsVisible = false,
            settingsConfigured = false,
        ))
        assertFalse(TVLibrarySurfacePolicy.shouldShowPairing(
            startupReady = true,
            homePhase = true,
            settingsVisible = false,
            settingsConfigured = true,
        ))
        assertFalse(TVLibrarySurfacePolicy.shouldShowPairing(
            startupReady = true,
            homePhase = false,
            settingsVisible = false,
            settingsConfigured = false,
        ))
        assertFalse(TVLibrarySurfacePolicy.shouldShowPairing(
            startupReady = true,
            homePhase = true,
            settingsVisible = true,
            settingsConfigured = false,
        ))
    }

    @Test
    fun `rapid library settings library sequence always blocks hidden receiver focus`() {
        assertTrue(TVLibrarySurfacePolicy.blocksReceiverHome(libraryVisible = true, settingsVisible = false))
        assertTrue(TVLibrarySurfacePolicy.blocksReceiverHome(libraryVisible = false, settingsVisible = true))
        assertTrue(TVLibrarySurfacePolicy.blocksReceiverHome(libraryVisible = true, settingsVisible = true))
        assertFalse(TVLibrarySurfacePolicy.blocksReceiverHome(libraryVisible = false, settingsVisible = false))
    }

    @Test
    fun `Tamil MV enters its catalog settings only after receiver setup exists`() {
        assertEquals(
            TVSettingsDestination.Pair,
            TVLibrarySurfacePolicy.settingsDestination(TVLibraryDestination.TamilMV, settingsConfigured = false),
        )
        assertEquals(
            TVSettingsDestination.Addons,
            TVLibrarySurfacePolicy.settingsDestination(TVLibraryDestination.TamilMV, settingsConfigured = true),
        )
        assertEquals(
            TVSettingsDestination.Pair,
            TVLibrarySurfacePolicy.settingsDestination(TVLibraryDestination.Continue, settingsConfigured = true),
        )
        assertEquals(
            TVSettingsDestination.Metadata,
            TVLibrarySurfacePolicy.settingsDestination(TVLibraryDestination.TMDBCatalogs, settingsConfigured = true),
        )
    }
}
