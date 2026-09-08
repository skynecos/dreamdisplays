package com.dreamdisplays.platform.client.ui.catalog

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiRect

/** Local outline helper keeps the catalog overlay independent from wildcard UI-kit imports. */
internal fun GuiGraphicsCompat.drawOutline(r: UiRect, color: Int) {
    fill(r.x, r.y, r.right, r.y + 1, color)
    fill(r.x, r.bottom - 1, r.right, r.bottom, color)
    fill(r.x, r.y, r.x + 1, r.bottom, color)
    fill(r.right - 1, r.y, r.right, r.bottom, color)
}
