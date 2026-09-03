package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Source-compatible screen setter for UI code shared by Minecraft 1.21.x and 26.x.
 *
 * On versions where Minecraft still exposes a member `setScreen`, that member wins over this
 * extension. On 26.2+ the member moved behind `mc.gui`, so calls transparently fall back to the
 * project's reflection-backed [MinecraftScreenUtil].
 */
internal fun Minecraft.setScreen(screen: Screen?) {
    MinecraftScreenUtil.setScreen(this, screen)
}
