package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.media.LocalVttServer
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.net.CustomMediaGate
import com.dreamdisplays.shared.playback.PlaybackPermissions
import com.dreamdisplays.shared.subtitle.VttSubtitleSource
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Changes only the optional external WebVTT source, using the ordinary upstream video permissions. */
@PaperOnly
class SubtitleCommand {
    fun setUrl(sender: CommandSender, token: String, rawUrl: String) {
        val player = sender as? Player ?: return
        val data = editable(player, token) ?: return
        val source = VttSubtitleSource.normalize(rawUrl)
        if (source == null) {
            MessageUtil.sendMessage(player, "invalidURL")
            return
        }
        CustomMediaGate.refusalKey(source, PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom), player.uniqueId)?.let {
            MessageUtil.sendMessage(player, it)
            return
        }
        apply(player, data, source, "VTT altyazı adresi ayarlandı.")
    }

    fun setFile(sender: CommandSender, token: String, file: String) {
        val player = sender as? Player ?: return
        val data = editable(player, token) ?: return
        if (!LocalVttServer.isRunning) {
            message(player, "Yerel VTT sunucusu kapalı; media-server.properties ayarını kontrol et.")
            return
        }
        val source = LocalVttServer.referenceForExisting(file)
        if (source == null) {
            message(player, "VTT bulunamadı. Dosya DreamDisplays/media altında olmalı (en fazla 2 MB).")
            return
        }
        apply(player, data, source, "Yerel VTT altyazısı ayarlandı.")
    }

    fun clear(sender: CommandSender, token: String) {
        val player = sender as? Player ?: return
        val data = editable(player, token) ?: return
        apply(player, data, "", "Harici VTT altyazısı kaldırıldı.")
    }

    private fun editable(player: Player, token: String): PaperDisplayData? {
        val data = resolvePaperDisplayTarget(player, player, token) as? PaperDisplayData ?: return null
        if (!PlaybackPermissions.canSetVideo(PlaybackContexts.of(data, player.uniqueId,
                player.hasPermission(PaperServer.config.permissions.deleteOthers)))) {
            MessageUtil.sendMessage(player, "displayVideoNotOwner")
            return null
        }
        return data
    }

    private fun apply(player: Player, data: PaperDisplayData, source: String, success: String) {
        val bytes = source.toByteArray(Charsets.UTF_8)
        if (source.isNotEmpty() && !DisplayCustomDataLimits.accepts(data.customData, VttSubtitleSource.KEY, bytes)) {
            message(player, "Ekranın özel veri sınırı dolu; altyazı kaydedilemedi.")
            return
        }
        val changed = if (source.isEmpty()) data.customData.remove(VttSubtitleSource.KEY) != null else {
            val different = data.customData[VttSubtitleSource.KEY]?.contentEquals(bytes) != true
            if (different) data.customData[VttSubtitleSource.KEY] = bytes
            different
        }
        if (changed) {
            runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
            DisplayManager.broadcastUpdate(data)
            ServerApiHost.current?.dataChanged(data, setOf(VttSubtitleSource.KEY))
        }
        message(player, success)
    }

    private fun message(player: Player, text: String) = MessageUtil.sendColoredMessage(player, "&7D | &f$text")
}
