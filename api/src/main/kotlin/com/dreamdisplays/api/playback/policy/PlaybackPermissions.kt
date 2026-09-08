package com.dreamdisplays.api.playback.policy

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.playback.model.PlaybackContext
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackMode.*

/**
 * The single source of truth for who may do what in each [PlaybackMode].
 *
 * @since 1.8.x
 */
@Unstable
object PlaybackPermissions {
    /** Max video height for [BROADCAST] displays; never exceeded, not even by the owner. */
    const val BROADCAST_QUALITY_CAP = 720

    /**
     * Whether this player may open the display-management UI or mutate shared display state.
     *
     * Lock state is deliberately ignored: an unlocked display is viewable, not publicly editable. Ownership alone never grants management.
     */
    fun canManageDisplay(c: PlaybackContext): Boolean = c.isAdmin

    /** Play / pause the shared timeline. Viewers are always read-only. */
    fun canPlayPause(c: PlaybackContext): Boolean = when (c.mode) {
        LOCAL, SYNCED -> canManageDisplay(c)
        WATCH_PARTY -> canManageDisplay(c) && c.isPartyHost
        BROADCAST -> false
    }

    /** Seek the shared timeline (same authority as play / pause). */
    fun canSeek(c: PlaybackContext): Boolean = canPlayPause(c)

    /** Change the display's video URL. Viewers can never send SetVideo, even while unlocked. */
    fun canSetVideo(c: PlaybackContext): Boolean = when (c.mode) {
        WATCH_PARTY -> canManageDisplay(c) && c.isPartyHost
        else -> canManageDisplay(c)
    }

    /** Change the persistent base mode. Forbidden while a watch party is live. */
    fun canSetMode(c: PlaybackContext): Boolean =
        canManageDisplay(c) && !c.hasActiveParty

    /** Toggle the base lock. Impossible in Watch party / Broadcast (forced-locked there). */
    fun canToggleLock(c: PlaybackContext): Boolean =
        canManageDisplay(c) && c.mode != WATCH_PARTY && c.mode != BROADCAST

    /** Change the (personal) video quality. Broadcast is hard-capped and cannot be changed. */
    fun canChangeQuality(c: PlaybackContext): Boolean =
        c.mode != BROADCAST

    /** Open the Picture-in-Picture / windowed popout. Forbidden in Broadcast for everyone (owner / admin included). */
    fun canPopout(c: PlaybackContext): Boolean =
        c.mode != BROADCAST

    /** Start a watch party. Shared-session creation is admin-only. */
    fun canStartWatchParty(c: PlaybackContext): Boolean =
        !c.hasActiveParty && canManageDisplay(c)

    /** Drive an active session (begin / pause / seek / end / restart). Managing host only. */
    fun canControlWatchParty(c: PlaybackContext): Boolean =
        canManageDisplay(c) && c.isPartyHost

    /** Close a session and free the display. An admin may recover a dead-host session. */
    fun canCloseWatchParty(c: PlaybackContext): Boolean =
        canManageDisplay(c)

    /** The lock the world actually sees: the base lock, or forced on by `Watch party` / `Broadcast`. */
    fun isEffectivelyLocked(mode: PlaybackMode, baseLocked: Boolean): Boolean =
        baseLocked || mode == WATCH_PARTY || mode == BROADCAST
}
