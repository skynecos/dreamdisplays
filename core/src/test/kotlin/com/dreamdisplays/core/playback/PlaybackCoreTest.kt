package com.dreamdisplays.core.playback

import com.dreamdisplays.api.playback.model.PlaybackContext
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.playback.model.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCoreTest {
    @Test
    fun runningTimelineAdvancesWithWallClock() {
        val t = Timeline(positionMs = 1_000, serverTimeMs = 10_000, paused = false)
        assertEquals(1_000, t.positionAt(10_000))
        assertEquals(3_500, t.positionAt(12_500))
    }

    @Test
    fun pausedTimelineIsFrozen() {
        val t = Timeline(positionMs = 5_000, serverTimeMs = 10_000, paused = true)
        assertEquals(5_000, t.positionAt(99_999))
    }

    @Test
    fun loopWrapsAroundDuration() {
        val t = Timeline(positionMs = 0, serverTimeMs = 0, paused = false, durationMs = 1_000, loop = true)
        assertEquals(500, t.positionAt(2_500))
        assertEquals(0, t.positionAt(3_000))
    }

    @Test
    fun pauseKeepsPositionContinuous() {
        val running = Timeline(positionMs = 0, serverTimeMs = 0, paused = false)
        val paused = running.withPaused(true, nowMs = 4_000)
        assertEquals(4_000, paused.positionMs)
        assertEquals(4_000, paused.positionAt(99_999))
    }

    private fun ctx(
        mode: PlaybackMode,
        owner: Boolean = false,
        admin: Boolean = false,
        locked: Boolean = true,
        party: Boolean = false,
        host: Boolean = false,
    ) = PlaybackContext(mode, owner, admin, locked, party, host)

    @Test
    fun localControlsAreOwnerOrAdminOnly() {
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.LOCAL, locked = false)))
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.LOCAL, locked = true)))
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.LOCAL, owner = true, locked = true)))
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.LOCAL, admin = true, locked = false)))
    }

    @Test
    fun syncedControlsAreOwnerOrAdminOnly() {
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.SYNCED, locked = false)))
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.SYNCED, locked = true)))
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.SYNCED, owner = true)))
        assertTrue(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.SYNCED, admin = true)))
    }

    @Test
    fun unlockedViewerCannotMutateSharedDisplayState() {
        val viewer = ctx(PlaybackMode.LOCAL, locked = false)
        assertFalse(PlaybackPermissions.canManageDisplay(viewer))
        assertFalse(PlaybackPermissions.canSetVideo(viewer))
        assertFalse(PlaybackPermissions.canSetMode(viewer))
        assertFalse(PlaybackPermissions.canStartWatchParty(viewer))
        assertFalse(PlaybackPermissions.canToggleLock(viewer))
    }

    @Test
    fun watchPartyIsHostOnlyAndForcedLocked() {
        assertTrue(PlaybackPermissions.canControlWatchParty(ctx(PlaybackMode.WATCH_PARTY, owner = true, host = true)))
        assertFalse(PlaybackPermissions.canControlWatchParty(ctx(PlaybackMode.WATCH_PARTY, owner = true, host = false)))
        assertFalse(PlaybackPermissions.canToggleLock(ctx(PlaybackMode.WATCH_PARTY, admin = true)))
        assertTrue(PlaybackPermissions.isEffectivelyLocked(PlaybackMode.WATCH_PARTY, baseLocked = false))
    }

    @Test
    fun broadcastLocksEverythingDown() {
        assertFalse(PlaybackPermissions.canPlayPause(ctx(PlaybackMode.BROADCAST, owner = true)))
        assertFalse(PlaybackPermissions.canChangeQuality(ctx(PlaybackMode.BROADCAST, owner = true)))
        assertFalse(PlaybackPermissions.canToggleLock(ctx(PlaybackMode.BROADCAST, admin = true)))
        assertTrue(PlaybackPermissions.isEffectivelyLocked(PlaybackMode.BROADCAST, baseLocked = false))
    }

    @Test
    fun startWatchPartyIsOwnerOrAdminOnly() {
        assertFalse(PlaybackPermissions.canStartWatchParty(ctx(PlaybackMode.SYNCED, locked = false)))
        assertFalse(PlaybackPermissions.canStartWatchParty(ctx(PlaybackMode.SYNCED, locked = true)))
        assertTrue(PlaybackPermissions.canStartWatchParty(ctx(PlaybackMode.SYNCED, owner = true, locked = true)))
        assertTrue(PlaybackPermissions.canStartWatchParty(ctx(PlaybackMode.SYNCED, admin = true, locked = false)))
    }
}
