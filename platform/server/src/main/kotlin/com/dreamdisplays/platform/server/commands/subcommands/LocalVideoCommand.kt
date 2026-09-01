package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.security.model.LanguageTag
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.media.LocalMediaServer
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.TimelineManager
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Assigns an MP4 below `plugins/DreamDisplays/media` without transcoding it. */
@PaperOnly
class LocalVideoCommand {
    fun execute(sender: CommandSender, token: String, file: String, lang: String) {
        val player = sender as? Player ?: return
        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.delete))
            )
        ) {
            sendMediaMessage(player, "§cBu ekranın videosunu değiştiremezsin.")
            return
        }
        if (!LocalMediaServer.isRunning) {
            sendMediaMessage(player, "§cYerel medya sunucusu çalışmıyor; media-server.properties dosyasını kontrol et.")
            return
        }

        val source = LocalMediaServer.referenceForExisting(file, "mp4")
        if (source == null) {
            sendMediaMessage(player, "§cMP4 bulunamadı veya dosya yolu geçersiz: §f${LocalMediaServer.mediaDirectory()}")
            return
        }

        val videoChanged = data.url != source
        val wasSync = data.isSync
        data.url = source
        data.lang = LanguageTag.canonicalAudioCode(lang).value
        if (videoChanged) data.subtitleUrl = ""

        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        DisplayManager.broadcastUpdate(data)
        if (wasSync) StateManager.resetAndBroadcast(data)
        TimelineManager.onVideoChanged(data)
        sendMediaMessage(player, "§fYerel MP4 ayarlandı: §d$file")
    }
}

internal fun sendMediaMessage(sender: CommandSender, message: String) {
    sender.sendMessage("§dKirazium §5» §7$message")
}
