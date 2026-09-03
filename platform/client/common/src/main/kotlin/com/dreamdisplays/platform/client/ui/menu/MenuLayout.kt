package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.widgets.SuggestionsPanel
import kotlin.math.max
import kotlin.math.min

/**
 * Responsive panel layout for the display menu. The catalog shelf always occupies the first content
 * band, then the existing preview/settings/suggestions layout uses the remaining space unchanged.
 */
class MenuLayout private constructor(
    val catalog: UiRect,
    val preview: UiRect,
    val settings: UiRect,
    val suggestions: UiRect?,
    val suggestionsVertical: Boolean,
) {
    companion object {
        const val CATALOG_H = 68

        /** Computes the panel layout for a [screenW] x [screenH] screen with the given font [lineHeight]. */
        fun compute(screenW: Int, screenH: Int, lineHeight: Int): MenuLayout {
            val pad = UiTheme.SCREEN_PADDING
            val gap = UiTheme.PANEL_GAP
            val titleY = 6
            val catalogTop = titleY + lineHeight + 8
            val contentTop = catalogTop + CATALOG_H + gap
            val contentBottom = screenH - pad
            val totalW = screenW - pad * 2
            val totalH = max(1, contentBottom - contentTop)
            val leftX = pad
            val catalog = UiRect(leftX, catalogTop, totalW, CATALOG_H)

            val wide = totalW >= 900 && totalH >= 480
            val compact = !wide && totalW < 600

            if (wide) {
                val rightColW = max(200, min(280, totalW * 3 / 10))
                val leftColW = totalW - rightColW - gap
                // The video is letterboxed to the screen's own aspect ratio, so height (not width)
                // is almost always its limiting dimension here; give it as much as the settings
                // panel below can spare instead of a flat 60/40 split, so it isn't left tiny inside
                // a much wider column.
                val settingsMinH = 220 // +30 to fit the 3D-audio settings row alongside the existing four
                var previewSlice = (totalH * 8) / 10
                if (totalH - previewSlice - gap < settingsMinH) {
                    previewSlice = max(1, totalH - settingsMinH - gap)
                }
                return MenuLayout(
                    catalog = catalog,
                    preview = UiRect(leftX, contentTop, leftColW, previewSlice),
                    settings = UiRect(leftX, contentTop + previewSlice + gap, leftColW, max(1, totalH - previewSlice - gap)),
                    suggestions = UiRect(leftX + leftColW + gap, contentTop, rightColW, totalH),
                    suggestionsVertical = true,
                )
            }

            // The horizontal suggestions strip is a fixed-height band, not a fraction of the screen:
            // it must fit its own header + search row (STRIP_CHROME_H) plus a full result card
            // (16:9 thumbnail + two title lines + meta = FULL_CARD_VIEWPORT_H). Reserve that comfortable
            // height so cards are never clipped, then hand everything else to the preview/settings row
            // (which the user wants as large as possible). On short screens it shrinks toward a floor
            // that still shows an un-clipped, if smaller, card; below that it's dropped entirely.
            val idealSuggestionsH = SuggestionsPanel.STRIP_CHROME_H + SuggestionsPanel.FULL_CARD_VIEWPORT_H
            val minSuggestionsH = SuggestionsPanel.STRIP_CHROME_H + SuggestionsPanel.MIN_CARD_VIEWPORT_H
            val topRowFloor = 230 // +30 to fit the 3D-audio settings row alongside the existing four
            var suggestionsH = idealSuggestionsH
                .coerceAtMost(max(minSuggestionsH, totalH - topRowFloor - gap))
                .coerceAtLeast(minSuggestionsH)
            var topRowH = totalH - suggestionsH - gap
            val showSuggestions = topRowH >= 150 && suggestionsH >= minSuggestionsH
            if (!showSuggestions) topRowH = totalH

            val preview: UiRect
            val settings: UiRect
            if (compact) {
                val previewH = min(220, max(1, topRowH * 3 / 5))
                preview = UiRect(leftX, contentTop, totalW, previewH)
                settings = UiRect(leftX, contentTop + previewH + gap, totalW, max(1, topRowH - previewH - gap))
            } else {
                val previewW = (totalW * 6) / 10 - gap / 2
                preview = UiRect(leftX, contentTop, previewW, topRowH)
                settings = UiRect(leftX + previewW + gap, contentTop, totalW - previewW - gap, topRowH)
            }
            return MenuLayout(
                catalog = catalog,
                preview = preview,
                settings = settings,
                suggestions = if (showSuggestions)
                    UiRect(leftX, contentTop + topRowH + gap, totalW, suggestionsH) else null,
                suggestionsVertical = false,
            )
        }
    }
}
