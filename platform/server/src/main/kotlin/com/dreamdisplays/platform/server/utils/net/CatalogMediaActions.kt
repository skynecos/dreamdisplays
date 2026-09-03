package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.security.policy.MediaUrlPolicy
import com.dreamdisplays.core.catalog.KiraziumCatalog
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.TimelineManager
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import java.util.UUID

/** Server-authoritative, atomic video + WebVTT selection used by the built-in media catalog. */
@PaperOnly
object CatalogMediaActions {
    private val throttle = ActionThrottle()
    private const val COOLDOWN_MS = 250L

    fun setMedia(player: Player, displayId: UUID, rawVideoUrl: String, lang: String, rawSubtitleUrl: String) {
        val display = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: return
        val context = PlaybackContexts.of(
            display,
            player.uniqueId,
            player.hasPermission(PaperServer.config.permissions.delete),
        )
        if (!PlaybackPermissions.canSetVideo(context)) return

        // Catalog packets are not a custom-media escape hatch: the exact video + subtitle pair must
        // already exist in the shared server-side allowlist. Never trust a modified client's raw URLs.
        val episode = KiraziumCatalog.findByMedia(rawVideoUrl, rawSubtitleUrl) ?: return
        if (!MediaUrlPolicy.isAllowed(episode.videoUrl) || !MediaUrlPolicy.isAllowed(episode.subtitleUrl)) return

        if (!DisplayManager.isPlayerInRange(player, display)) return
        if (!throttle.tryAcquire(displayId, COOLDOWN_MS)) return

        val videoChanged = display.url != episode.videoUrl
        val wasSync = display.isSync
        display.url = episode.videoUrl
        display.lang = MediaUrlPolicy.sanitizeLang(lang)
        display.subtitleUrl = episode.subtitleUrl

        runAsync { PaperServer.getInstance().storage.saveDisplay(display) }
        DisplayManager.broadcastUpdate(display)
        if (wasSync) StateManager.resetAndBroadcast(display)
        if (videoChanged) TimelineManager.onVideoChanged(display)
    }
}
