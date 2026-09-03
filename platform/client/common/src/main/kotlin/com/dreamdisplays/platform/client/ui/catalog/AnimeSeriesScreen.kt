package com.dreamdisplays.platform.client.ui.catalog

import com.dreamdisplays.core.protocol.common.packets.SetMediaWithSubtitle
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawPanel
import com.dreamdisplays.platform.client.ui.widgets.TextButton
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.min

/** Season / episode picker opened from the series carousel in [com.dreamdisplays.platform.client.ui.DisplayMenu]. */
class AnimeSeriesScreen(
    private val returnTo: Screen,
    private val displayScreen: DisplayScreen,
    private val series: AnimeSeries,
) : UiScreenBase(Component.literal(series.title)) {

    private data class EpisodeButton(
        val season: Int,
        val index: Int,
        val episode: AnimeEpisode,
        val button: TextButton,
    )

    private var selectedSeason: Int = series.seasons.firstOrNull()?.number ?: 1
    private val seasonButtons = mutableListOf<Pair<AnimeSeason, TextButton>>()
    private val episodeButtons = mutableListOf<EpisodeButton>()
    private lateinit var backButton: TextButton

    override fun init() {
        super.init()
        seasonButtons.clear()
        episodeButtons.clear()

        series.seasons.forEach { season ->
            val button = addUi(TextButton(Component.literal("Sezon ${season.number}")) {
                selectedSeason = season.number
            })
            seasonButtons += season to button

            season.episodes.forEachIndexed { index, episode ->
                val episodeButton = addUi(TextButton(Component.literal("Bölüm ${episode.number}")) {
                    playEpisode(episode)
                })
                episodeButton.visibleWhen = { selectedSeason == season.number }
                episodeButton.enabledWhen = { displayScreen.canSetVideoHere }
                episodeButtons += EpisodeButton(season.number, index, episode, episodeButton)
            }
        }

        backButton = addUi(TextButton(Component.literal("Geri")) { onClose() })
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)

        val panelW = min(560, width - 32).coerceAtLeast(360)
        val panelH = min(330, height - 40).coerceAtLeast(260)
        val panel = UiRect((width - panelW) / 2, (height - panelH) / 2, panelW, panelH)
        g.drawPanel(font, panel, series.title)

        g.drawText(
            font,
            "Oynatmak istediğin sezon ve bölümü seç.",
            panel.x + UiTheme.PANEL_PADDING_X,
            panel.y + 27,
            UiTheme.TEXT_SECONDARY,
            false,
        )

        val seasonY = panel.y + 48
        val seasonW = 86
        val seasonGap = 6
        seasonButtons.forEachIndexed { index, (_, button) ->
            button.place(UiRect(panel.x + 10 + index * (seasonW + seasonGap), seasonY, seasonW, 22))
        }

        g.drawText(font, "Bölümler", panel.x + 10, seasonY + 33, UiTheme.TEXT_PRIMARY, false)
        val episodeY = seasonY + 48
        val episodeW = panel.w - 20
        val episodeH = 24
        val episodeGap = 5
        episodeButtons.forEach { binding ->
            binding.button.place(
                UiRect(
                    panel.x + 10,
                    episodeY + binding.index * (episodeH + episodeGap),
                    episodeW,
                    episodeH,
                ),
            )
        }

        if (!displayScreen.canSetVideoHere) {
            g.drawText(
                font,
                "Bu display kilitli; bölüm seçmek için kontrol yetkisi gerekiyor.",
                panel.x + 10,
                panel.bottom - 48,
                UiTheme.TEXT_SECONDARY,
                false,
            )
        }

        backButton.place(UiRect(panel.right - 82, panel.bottom - 32, 72, 22))
        drawChildren(g, mouseX, mouseY, partialTick)
    }

    private fun playEpisode(episode: AnimeEpisode) {
        if (!displayScreen.canSetVideoHere) return
        Initializer.sendPacket(
            SetMediaWithSubtitle(
                id = displayScreen.uuid,
                url = episode.videoUrl,
                lang = displayScreen.lang ?: "",
                subtitleUrl = episode.subtitleUrl,
            ),
        )
        Minecraft.getInstance().setScreen(returnTo)
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(returnTo)
    }

    override fun isPauseScreen(): Boolean = false

    override fun minContentSize(): Pair<Int, Int> = 620 to 400
}
