package com.dreamdisplays.platform.client.ui.catalog

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.catalog.AnimeCatalog
import com.dreamdisplays.platform.client.catalog.AnimeEpisode
import com.dreamdisplays.platform.client.catalog.AnimeSeason
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
import java.util.UUID
import kotlin.math.max

/**
 * Kirazium's compact, horizontally scrollable media shelf. It deliberately lives in the reserved
 * top band of [DisplayMenu], so catalog clicks can never hit a playback/settings widget underneath.
 * Navigation is three-level: series -> season -> episode.
 */
object AnimeCatalogOverlay {
    private enum class Level { SERIES, SEASONS, EPISODES }

    private var level = Level.SERIES
    private var selectedSeries: AnimeSeries? = null
    private var selectedSeason: AnimeSeason? = null
    private var scrollOffset = 0
    private var lastDisplayId: UUID? = null
    private var wasLeftPressed = false

    private const val PAD = 8
    private const val HEADER_H = 18
    private const val CARD_H = 32
    private const val CARD_GAP = 6
    private const val NAV_W = 22
    private const val BACK_W = 48

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
            Level.SEASONS -> "Anime Kataloğu  ›  ${selectedSeries?.title.orEmpty()}"
            Level.EPISODES -> "${selectedSeries?.title.orEmpty()}  ›  Sezon ${selectedSeason?.number ?: 1}"
        }
        g.drawText(font, breadcrumb, shelf.x + PAD, shelf.y + 6, UiTheme.TEXT_PRIMARY, true)

        val rowY = shelf.y + HEADER_H + 8
        val rowH = minOf(CARD_H, shelf.bottom - rowY - 5)
        if (rowH <= 8) return

        var left = shelf.x + PAD
        if (level != Level.SERIES) {
            val back = UiRect(left, rowY, BACK_W, rowH)
            drawButton(g, font, back, "‹ Geri", back.contains(mouseX, mouseY), true)
            if (clicked && back.contains(mouseX, mouseY)) {
                goBack()
                return
            }
            left += BACK_W + CARD_GAP
        }

        when (level) {
            Level.SERIES -> drawSeries(menu, g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked)
            Level.SEASONS -> drawSeasons(g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked)
            Level.EPISODES -> drawEpisodes(menu, g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked)
        }
    }

    private fun drawSeries(
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
        drawCards(
            g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked,
            items = AnimeCatalog.series,
            label = { it.title },
            enabled = { true },
            active = { false },
        ) { series ->
            selectedSeries = series
            selectedSeason = null
            level = Level.SEASONS
            scrollOffset = 0
        }
    }

    private fun drawSeasons(
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
        val seasons = selectedSeries?.seasons.orEmpty()
        drawCards(
            g, font, shelf, left, rowY, rowH, mouseX, mouseY, clicked,
            items = seasons,
            label = { "Sezon ${it.number}" },
            enabled = { it.episodes.isNotEmpty() },
            active = { false },
        ) { season ->
            selectedSeason = season
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
        val episodes = selectedSeason?.episodes.orEmpty()
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
        onPick: (T) -> Unit,
    ) {
        if (items.isEmpty()) {
            g.drawText(font, "Henüz içerik yok.", startX, rowY + (rowH - font.lineHeight) / 2, UiTheme.TEXT_SECONDARY, false)
            return
        }

        val availableW = max(1, shelf.right - PAD - startX)
        val preferredCardW = when (level) {
            Level.EPISODES -> 94
            else -> 118
        }
        val noNavVisible = max(1, (availableW + CARD_GAP) / (preferredCardW + CARD_GAP))
        val overflow = items.size > noNavVisible
        val navSpace = if (overflow) (NAV_W + CARD_GAP) * 2 else 0
        val viewportW = max(1, availableW - navSpace)
        val visibleCount = max(1, (viewportW + CARD_GAP) / (preferredCardW + CARD_GAP))
        val cardW = max(54, (viewportW - CARD_GAP * (visibleCount - 1)) / visibleCount)
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
            drawButton(g, font, rect, label(item), rect.contains(mouseX, mouseY), isEnabled, isActive)
            if (clicked && isEnabled && rect.contains(mouseX, mouseY)) {
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
        when (level) {
            Level.SERIES -> Unit
            Level.SEASONS -> {
                selectedSeries = null
                level = Level.SERIES
            }
            Level.EPISODES -> {
                selectedSeason = null
                level = Level.SEASONS
            }
        }
        scrollOffset = 0
    }

    private fun resetNavigation() {
        level = Level.SERIES
        selectedSeries = null
        selectedSeason = null
        scrollOffset = 0
        wasLeftPressed = false
    }
}
