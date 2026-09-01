package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.media.source.url.CustomMediaUrls
import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.security.policy.MediaUrlPolicy
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.media.LocalMediaServer
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.net.CustomMediaGate
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Configures the optional WebVTT source independently from the video source. */
@PaperOnly
class SubtitleCommand {
    fun setUrl(sender: CommandSender, token: String, rawUrl: String) {
        val player = sender as? Player ?: return
        val data = editable(sender, player, token) ?: return
        val normalized = CustomMediaUrls.normalize(rawUrl)?.takeIf(MediaUrlPolicy::isAllowed)
        if (normalized == null) {
            MessageUtil.sendMessage(player, "invalidURL")
            return
        }
        CustomMediaGate.refusalKey(
            normalized,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let { refusal ->
            MessageUtil.sendMessage(player, refusal)
            return
        }
        apply(player, data, normalized, "§fVTT URL ayarlandı.")
    }

    fun setFile(sender: CommandSender, token: String, file: String) {
        val player = sender as? Player ?: return
        val data = editable(sender, player, token) ?: return
        if (!LocalMediaServer.isRunning) {
            sendMediaMessage(player, "§cYerel medya sunucusu çalışmıyor; media-server.properties dosyasını kontrol et.")
            return
        }
        val source = LocalMediaServer.referenceForExisting(file, "vtt")
        if (source == null) {
            sendMediaMessage(player, "§cVTT bulunamadı veya dosya yolu geçersiz: §f${LocalMediaServer.mediaDirectory()}")
            return
        }
        apply(player, data, source, "§fYerel VTT ayarlandı: §d$file")
    }

    fun clear(sender: CommandSender, token: String) {
        val player = sender as? Player ?: return
        val data = editable(sender, player, token) ?: return
        apply(player, data, "", "§fAltyazı kapatıldı.")
    }

    private fun editable(sender: CommandSender, player: Player, token: String): PaperDisplayData? {
        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return null
        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.delete))
            )
        ) {
            sendMediaMessage(player, "§cBu ekranın altyazısını değiştiremezsin.")
            return null
        }
        return data
    }

    private fun apply(player: Player, data: PaperDisplayData, source: String, message: String) {
        if (data.subtitleUrl != source) {
            data.subtitleUrl = source
            runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
            DisplayManager.broadcastUpdate(data)
        }
        sendMediaMessage(player, message)
    }
}
