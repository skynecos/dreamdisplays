package com.dreamdisplays.platform.client.ui.catalog

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.catalog.AnimeCatalog
import com.dreamdisplays.platform.client.catalog.AnimeEpisode
import com.dreamdisplays.platform.client.catalog.AnimeSeries
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.ui.DisplayMenu
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.menu.MenuLayout
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.util.UUID
import kotlin.math.max

/**
 * Kirazium's compact, horizontally scrollable media shelf. Naruto opens its episodes directly:
 * there is deliberately no season-selection step in the player UI.
 */
object AnimeCatalogOverlay {
    private enum class Level { SERIES, EPISODES }

    private var level = Level.SERIES
    private var selectedSeries: AnimeSeries? = null
    private var scrollOffset = 0
    private var lastDisplayId: UUID? = null
    private var wasLeftPressed = false

    private const val PAD = 8
    private const val HEADER_H = 18
    private const val SERIES_CARD_H = 68
    private const val EPISODE_CARD_H = 32
    private const val CARD_GAP = 6
    private const val NAV_W = 22
    private const val BACK_W = 48
    private const val ART_TEXTURE_W = 256
    private const val ART_TEXTURE_H = 144

    private val NARUTO_ART = Identifier.fromNamespaceAndPath(
        Initializer.MOD_ID,
        "textures/gui/catalog/naruto.png",
    )

    /** True only when the connected server explicitly supports atomic catalog video + subtitle picks. */
    private fun catalogSupported(): Boolean =
        ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.CATALOG_MEDIA)

    /** Resets transient mouse state when the display menu is not the active screen. */
    fun onOtherScreen() {
        wasLeftPressed = false
    }

    /** Draws the shelf and handles one edge-triggered left click per physical press. */
    fun render(menu: DisplayMenu, g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, leftPressed: Boolean) {
        val display = menu.displayScreen

        // Never expose catalog controls against an older/incompatible server, and never draw them on
        // top of DisplayMenu's loading-error panel. Track the physical press even while hidden so a
        // held mouse/touch cannot become an accidental catalog click when the state changes.
        if (!catalogSupported() || display.errored) {
            wasLeftPressed = leftPressed
            return
        }

        if (lastDisplayId != display.uuid) {
            lastDisplayId = display.uuid
            resetNavigation()
        }

        val font = Minecraft.getInstance().font
        val shelf = MenuLayout.compute(menu.width, menu.height, font.lineHeight).catalog
        val clicked = leftPressed && !wasLeftPressed
        wasLeftPressed = leftPressed

        g.fill(shelf.x, shelf.y, shelf.right, shelf.bottom, UiTheme.CARD_BG)
        g.drawOutline(shelf, UiTheme.PANEL_BORDER)

        val breadcrumb = when (level) {
            Level.SERIES -> "Anime Kataloğu"
            Level.EPISODES -> "Bölümler"
        }
        g.drawText(font, breadcrumb, shelf.x + PAD, shelf.y + 6, UiTheme.TEXT_PRIMARY, true)

        val rowY = shelf.y + HEADER_H + 8
        val desiredRowH = if (level == Level.SERIES) SERIES_CARD_H else EPISODE_CARD_H
        val rowH = minOf(desiredRowH, shelf.bottom - rowY - 5)
        if (rowH <= 8) return

        var left = shelf.x + PAD
        if (level == Level.EPISODES) {
            val back = UiRect(left, rowY, BACK_W, rowH)
            drawButton(g, font, back, "‹ Geri", back.contains(mouseX, mouseY), true)
            if (clicked && back.contains(mouseX, mouseY)) {
                goBack()
                return
            }
            left += BACK_W + CARD_GAP
        }

        when (level) {
            Level.SERIES -> drawSeries(g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked)
            Level.EPISODES -> drawEpisodes(menu, g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked)
        }
    }

    private fun drawSeries(
        g: GuiGraphicsCompat,
        font: Font,
        shelf: UiRect,
        left: Int,
        rowY: Int,
        rowH: Int,
        mouseX: Int,
        mouseY: Int,
        clicked: Boolean,
    ) {
        drawCards(
            g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked,
            items = AnimeCatalog.series,
            label = { it.title },
            enabled = { it.seasons.any { season -> season.episodes.isNotEmpty() } },
            active = { false },
            renderer = { rect, series, hovered, enabled, _ ->
                drawSeriesCard(g, font, rect, series, hovered, enabled)
            },
        ) { series ->
            selectedSeries = series
            level = Level.EPISODES
            scrollOffset = 0
        }
    }

    private fun drawEpisodes(
        menu: DisplayMenu,
        g: GuiGraphicsCompat,
        font: Font,
        shelf: UiRect,
        left: Int,
        rowY: Int,
        rowH: Int,
        mouseX: Int,
        mouseY: Int,
        clicked: Boolean,
    ) {
        val display = menu.displayScreen
        // The data model retains seasons for server validation, but the player sees one uninterrupted
        // episode list. This also keeps the UI correct if Naruto is grouped differently later.
        val episodes = selectedSeries?.seasons.orEmpty().flatMap { it.episodes }
        drawCards(
            g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked,
            items = episodes,
            label = { "${it.number}. Bölüm" },
            enabled = { display.canSetVideoHere },
            active = { display.videoUrl == it.videoUrl },
        ) { episode ->
            playEpisode(menu, episode)
        }
    }

    /** Generic one-row card strip with click arrows when content overflows. */
    private fun <T> drawCards(
        g: GuiGraphicsCompat,
        font: Font,
        shelf: UiRect,
        startX: Int,
        rowY: Int,
        rowH: Int,
        mouseX: Int,
        mouseY: Int,
        clicked: Boolean,
        items: List<T>,
        label: (T) -> String,
        enabled: (T) -> Boolean,
        active: (T) -> Boolean,
        renderer: ((UiRect, T, Boolean, Boolean, Boolean) -> Unit)? = null,
        onPick: (T) -> Unit,
    ) {
        if (items.isEmpty()) {
            g.drawText(font, "Henüz içerik yok.", startX, rowY + (rowH - font.lineHeight) / 2, UiTheme.TEXT_SECONDARY, false)
            return
        }

        val availableW = max(1, shelf.right - PAD - startX)
        val preferredCardW = when (level) {
            Level.SERIES -> rowH * 16 / 9
            Level.EPISODES -> 94
        }
        val noNavVisible = max(1, (availableW + CARD_GAP) / (preferredCardW + CARD_GAP))
        val overflow = items.size > noNavVisible
        val navSpace = if (overflow) (NAV_W + CARD_GAP) * 2 else 0
        val viewportW = max(1, availableW - navSpace)
        val visibleCount = max(1, (viewportW + CARD_GAP) / (preferredCardW + CARD_GAP))
        val cardW = max(54, minOf(preferredCardW, (viewportW - CARD_GAP * (visibleCount - 1)) / visibleCount))
        val maxOffset = max(0, items.size - visibleCount)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)

        var cardsX = startX
        if (overflow) {
            val prev = UiRect(startX, rowY, NAV_W, rowH)
            drawButton(g, font, prev, "‹", prev.contains(mouseX, mouseY), scrollOffset > 0)
            if (clicked && prev.contains(mouseX, mouseY) && scrollOffset > 0) {
                scrollOffset--
                return
            }
            cardsX += NAV_W + CARD_GAP
        }

        val endExclusive = minOf(items.size, scrollOffset + visibleCount)
        var x = cardsX
        for (i in scrollOffset until endExclusive) {
            val item = items[i]
            val rect = UiRect(x, rowY, cardW, rowH)
            val isEnabled = enabled(item)
            val isActive = active(item)
            val hovered = rect.contains(mouseX, mouseY)
            if (renderer != null) {
                renderer(rect, item, hovered, isEnabled, isActive)
            } else {
                drawButton(g, font, rect, label(item), hovered, isEnabled, isActive)
            }
            if (clicked && isEnabled && hovered) {
                onPick(item)
                return
            }
            x += cardW + CARD_GAP
        }

        if (overflow) {
            val nextX = shelf.right - PAD - NAV_W
            val next = UiRect(nextX, rowY, NAV_W, rowH)
            drawButton(g, font, next, "›", next.contains(mouseX, mouseY), scrollOffset < maxOffset)
            if (clicked && next.contains(mouseX, mouseY) && scrollOffset < maxOffset) {
                scrollOffset++
            }
        }
    }

    /** Draws Naruto's supplied 16:9 artwork instead of a text-only "Naruto" button. */
    private fun drawSeriesCard(
        g: GuiGraphicsCompat,
        font: Font,
        rect: UiRect,
        series: AnimeSeries,
        hovered: Boolean,
        enabled: Boolean,
    ) {
        if (series.id != "naruto") {
            drawButton(g, font, rect, series.title, hovered, enabled)
            return
        }

        g.fill(rect.x, rect.y, rect.right, rect.bottom, UiTheme.ROW_BG)
        blitTexture(g, NARUTO_ART, rect.x, rect.y, rect.w, rect.h)
        if (!enabled) {
            g.fill(rect.x, rect.y, rect.right, rect.bottom, 0x88000000.toInt())
        }
        g.drawOutline(rect, if (hovered && enabled) UiTheme.CARD_BORDER_HOVER else UiTheme.PANEL_BORDER)
    }

    private fun blitTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, ART_TEXTURE_W, ART_TEXTURE_H)
        //?} else
        /*g.blit(id, x, y, 0f, 0f, w, h, ART_TEXTURE_W, ART_TEXTURE_H)*/
    }

    private fun drawButton(
        g: GuiGraphicsCompat,
        font: Font,
        rect: UiRect,
        text: String,
        hovered: Boolean,
        enabled: Boolean,
        active: Boolean = false,
    ) {
        val bg = when {
            !enabled -> UiTheme.ROW_BG
            active -> UiTheme.ACCENT
            hovered -> UiTheme.CARD_BG_HOVER
            else -> UiTheme.CARD_BG
        }
        val color = if (enabled) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_SECONDARY
        g.fill(rect.x, rect.y, rect.right, rect.bottom, bg)
        if (hovered && enabled) g.drawOutline(rect, UiTheme.CARD_BORDER_HOVER)
        val clipped = if (font.width(text) <= rect.w - 8) text else {
            var value = text
            while (value.length > 1 && font.width("$value…") > rect.w - 8) value = value.dropLast(1)
            "$value…"
        }
        val tx = rect.x + (rect.w - font.width(clipped)) / 2
        val ty = rect.y + (rect.h - font.lineHeight) / 2
        g.drawText(font, clipped, tx, ty, color, true)
    }

    private fun playEpisode(menu: DisplayMenu, episode: AnimeEpisode) {
        val display = menu.displayScreen
        if (!catalogSupported() || !display.canSetVideoHere || display.errored) return
        Initializer.sendPacket(
            SetVideo(
                id = display.uuid,
                url = episode.videoUrl,
                lang = display.lang ?: "",
                subtitleUrl = episode.subtitleUrl,
                replaceSubtitle = true,
            ),
        )
    }

    private fun goBack() {
        if (level == Level.EPISODES) {
            selectedSeries = null
            level = Level.SERIES
        }
        scrollOffset = 0
    }

    private fun resetNavigation() {
        level = Level.SERIES
        selectedSeries = null
        scrollOffset = 0
        wasLeftPressed = false
    }
}
