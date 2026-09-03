package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.security.policy.MediaUrlPolicy
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import java.util.UUID

/** Server-authoritative actions used by the Kirazium catalog UI. */
@PaperOnly
object CatalogActions {
    private val setMediaThrottle = ActionThrottle()
    private const val SET_MEDIA_COOLDOWN_MS = 250L

    /**
     * Applies a video and its matching VTT as one mutation, then persists and broadcasts one display
     * snapshot. This prevents an episode switch from ever exposing the previous episode's subtitle URL.
     */
    fun setMediaWithSubtitle(
        player: Player,
        displayId: UUID,
        rawUrl: String,
        rawLang: String,
        rawSubtitleUrl: String,
    ) {
        val displayData = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        val context = PlaybackContexts.of(
            displayData,
            player.uniqueId,
            player.hasPermission(PaperServer.config.permissions.delete),
        )
        if (!PlaybackPermissions.canSetVideo(context)) return
        if (!DisplayManager.isPlayerInRange(player, displayData)) return
        if (!setMediaThrottle.tryAcquire(displayId, SET_MEDIA_COOLDOWN_MS)) return

        val url = rawUrl.trim()
        val subtitleUrl = rawSubtitleUrl.trim()
        if (!MediaUrlPolicy.isAllowed(url)) return
        if (subtitleUrl.isNotEmpty() && !MediaUrlPolicy.isAllowed(subtitleUrl)) return

        val hasCustomPermission = player.hasPermission(PaperServer.config.permissions.custom)
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            hasCustomPermission,
            player.uniqueId,
        )?.let { return MessageUtil.sendMessage(player, it) }
        if (subtitleUrl.isNotEmpty()) {
            CustomMediaGate.refusalKey(
                subtitleUrl,
                PaperServer.config.settings.customMediaPolicy,
                hasCustomPermission,
            )?.let { return MessageUtil.sendMessage(player, it) }
        }

        val videoChanged = displayData.url != url
        val wasSync = displayData.isSync
        displayData.url = url
        displayData.lang = MediaUrlPolicy.sanitizeLang(rawLang)
        displayData.subtitleUrl = subtitleUrl

        runAsync { PaperServer.getInstance().storage.saveDisplay(displayData) }
        DisplayManager.broadcastUpdate(displayData)
        if (videoChanged) {
            if (wasSync) StateManager.resetAndBroadcast(displayData)
            TimelineManager.onVideoChanged(displayData)
        }
    }
}
