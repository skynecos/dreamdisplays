package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.DisplayMenu
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.catalog.AnimeCatalog
import com.dreamdisplays.platform.client.ui.catalog.AnimeSeries
import com.dreamdisplays.platform.client.ui.catalog.AnimeSeriesScreen
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawOutline
import com.dreamdisplays.platform.client.ui.kit.drawPanelSprite
import com.dreamdisplays.util.GeneralUtil
import com.dreamdisplays.util.UpdateCheck
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.util.ARGB
//?}
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.awt.Desktop
import java.net.URI
import kotlin.math.max

/**
 * Compact top bar used by [DisplayMenu]. It keeps the version label and adds a horizontally paged
 * series carousel; cards open a separate season / episode screen.
 */
class ModTitleLabel {
    private var firstSeries = 0
    private var versionArea: UiRect? = null
    private var leftArea: UiRect? = null
    private var rightArea: UiRect? = null
    private val cardAreas = mutableListOf<Pair<AnimeSeries, UiRect>>()

    fun draw(g: GuiGraphicsCompat, x: Int, y: Int) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val screenW = mc.screen?.width ?: 640

        g.drawText(font, "Seriler", x, y, UiTheme.TEXT_PRIMARY, true)

        val version = Component.literal("Dream Displays ${GeneralUtil.getPrettyModVersion()}")
            .withStyle(Style.EMPTY.withColor(UiTheme.ACCENT_VERSION))
        val versionW = font.width(version)
        val versionX = max(x + 90, screenW - x - versionW)
        g.drawText(font, version, versionX, y, UiTheme.TEXT_PRIMARY, false)
        versionArea = UiRect(versionX, y - 1, versionW, font.lineHeight + 2)

        if (UpdateCheck.shouldShowArrow()) {
            val update = Component.literal(" ▲").withStyle(Style.EMPTY.withColor(UiTheme.ACCENT_UPDATE))
            g.drawText(font, update, versionX + versionW, y, UiTheme.TEXT_PRIMARY, false)
        }

        val series = AnimeCatalog.series
        cardAreas.clear()
        leftArea = null
        rightArea = null
        if (series.isEmpty()) return

        val rowY = y + font.lineHeight + 5
        val availableW = (screenW - x * 2).coerceAtLeast(CARD_W)
        val maxWithoutArrows = max(1, (availableW + CARD_GAP) / (CARD_W + CARD_GAP))
        val hasOverflow = series.size > maxWithoutArrows
        val arrowSpace = if (hasOverflow) ARROW_W + CARD_GAP else 0
        val viewportW = availableW - arrowSpace * 2
        val visibleCount = max(1, (viewportW + CARD_GAP) / (CARD_W + CARD_GAP))
        firstSeries = firstSeries.coerceIn(0, max(0, series.size - visibleCount))

        var cardX = x
        if (hasOverflow) {
            val left = UiRect(x, rowY, ARROW_W, CARD_H)
            drawArrow(g, left, "‹", firstSeries > 0)
            leftArea = left
            cardX += ARROW_W + CARD_GAP
        }

        series.drop(firstSeries).take(visibleCount).forEach { item ->
            val card = UiRect(cardX, rowY, CARD_W, CARD_H)
            drawSeriesCard(g, item, card)
            cardAreas += item to card
            cardX += CARD_W + CARD_GAP
        }

        if (hasOverflow) {
            val right = UiRect(screenW - x - ARROW_W, rowY, ARROW_W, CARD_H)
            drawArrow(g, right, "›", firstSeries + visibleCount < series.size)
            rightArea = right
        }
    }

    private fun drawSeriesCard(g: GuiGraphicsCompat, series: AnimeSeries, card: UiRect) {
        g.drawPanelSprite(card)
        val art = UiRect(card.x + 1, card.y + 1, card.w - 2, card.h - 2)
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, series.artwork, art.x, art.y, art.w, art.h, ARGB.white(1f))
        //?} else
        /*g.blitSprite(series.artwork, art.x, art.y, art.w, art.h)*/

        val labelH = 13
        g.fill(card.x + 1, card.bottom - labelH - 1, card.right - 1, card.bottom - 1, 0xB0000000.toInt())
        val font = Minecraft.getInstance().font
        g.drawText(font, series.title, card.x + 5, card.bottom - labelH, UiTheme.TEXT_PRIMARY, true)
        g.drawOutline(card, UiTheme.PANEL_BORDER)
    }

    private fun drawArrow(g: GuiGraphicsCompat, area: UiRect, text: String, enabled: Boolean) {
        g.drawPanelSprite(area, alpha = if (enabled) 1f else 0.45f)
        val font = Minecraft.getInstance().font
        val color = if (enabled) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_SECONDARY
        val tx = area.x + (area.w - font.width(text)) / 2
        val ty = area.y + (area.h - font.lineHeight) / 2
        g.drawText(font, text, tx, ty, color, false)
    }

    fun handleClick(mx: Int, my: Int): Boolean {
        if (versionArea?.contains(mx, my) == true && UpdateCheck.shouldShowArrow()) {
            try {
                Desktop.getDesktop().browse(URI.create(MODRINTH_URL))
            } catch (_: Exception) {
            }
            return true
        }

        if (leftArea?.contains(mx, my) == true && firstSeries > 0) {
            firstSeries--
            return true
        }
        if (rightArea?.contains(mx, my) == true) {
            firstSeries++
            return true
        }

        val picked = cardAreas.firstOrNull { (_, area) -> area.contains(mx, my) }?.first ?: return false
        val menu = Minecraft.getInstance().screen as? DisplayMenu ?: return false
        Minecraft.getInstance().setScreen(AnimeSeriesScreen(menu, menu.displayScreen, picked))
        return true
    }

    companion object {
        private const val CARD_W = 112
        private const val CARD_H = 48
        private const val CARD_GAP = 6
        private const val ARROW_W = 18
        private const val MODRINTH_URL = "https://modrinth.com/plugin/dreamdisplays/versions"
    }
}
