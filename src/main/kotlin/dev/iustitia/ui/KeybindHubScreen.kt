package dev.iustitia.ui

import dev.iustitia.keybind.Keybinds
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component

/**
 * #14 (keybind hub): a manual-render screen listing every Iustitia keybind, its currently bound
 * key, and a one-line description. Any bind whose key is also held by another (non-identical)
 * keybinding is highlighted `§c` red with the conflicting binding's name, so the user knows to
 * rebind before it collides at runtime. Display-only — rebinds happen in vanilla Controls →
 * Iustitia (the binds are standard [net.minecraft.client.KeyMapping]s). Fail-open.
 */
class KeybindHubScreen(private val parent: Screen?) : Screen(TITLE) {

    /** Per-bind conflict: the other keybinding sharing this bind's key, or null if clean. */
    private data class Row(val bind: Keybinds.Bind, val conflict: KeyMapping?)

    private var rows: List<Row> = emptyList()

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        try { rebuild() } catch (_: Throwable) {}
    }

    private fun rebuild() {
        try {
            val client = this.minecraft ?: run { rows = emptyList(); return }
            // Every registered keybinding in the game (vanilla + mods), for conflict detection.
            // KeyMapping's bound-key field is protected, so compare via Fabric's public accessor
            // [KeyMappingHelper.getBoundKeyOf] — two binds sharing a physical key are equal keys.
            val allKeys: Array<KeyMapping> = try { client.options.keyMappings } catch (_: Throwable) { emptyArray() }
            rows = Keybinds.all.map { b ->
                val myKey = try { b.keyBinding?.let { KeyMappingHelper.getBoundKeyOf(it) } } catch (_: Throwable) { null }
                val conflict = if (myKey == null) null else allKeys.firstOrNull { other ->
                    other !== b.keyBinding &&
                        try { KeyMappingHelper.getBoundKeyOf(other) == myKey } catch (_: Throwable) { false }
                }
                Row(b, conflict)
            }
        } catch (_: Throwable) { rows = emptyList() }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            context.fill(0, 0, this.width, this.height, BG)
            super.extractRenderState(context, mouseX, mouseY, delta)
            val tr = this.font
            context.text(tr, Component.literal("§8[§diustitia§8] §f§lKeybind hub"), 10, 10, WHITE)
            context.text(tr, Component.literal("§7Rebind any of these in §fOptions → Controls → Iustitia§7. Red = key conflict."), 10, 24, WHITE)
            var y = 44
            for (r in rows) {
                try {
                    val conflictColor = r.conflict != null
                    val kb = r.bind.keyBinding
                    val keyText = if (kb == null) Component.literal("§cunregistered")
                        else try { kb.translatedKeyMessage } catch (_: Throwable) { Component.literal("?") }
                    val keyStr = "§f${keyText.string}"
                    val nameColor = if (conflictColor) "§c" else "§f"
                    context.text(tr, Component.literal("$nameColor${r.bind.label}"), 14, y, WHITE)
                    val keyDrawn = Component.literal("§7[§r$keyStr§7]")
                    context.text(tr, keyDrawn, 200, y, WHITE)
                    context.text(tr, Component.literal("§7${r.bind.description}"), 14, y + 11, WHITE)
                    if (conflictColor && r.conflict != null) {
                        val otherName = try { Component.translatable(r.conflict.name).string }
                        catch (_: Throwable) { r.conflict.name }
                        context.text(tr, Component.literal("§c⚠ conflicts with §f$otherName"), 200, y + 11, WHITE)
                    }
                } catch (_: Throwable) {}
                y += 26
            }
        } catch (_: Throwable) {}
    }

    override fun onClose() {
        val p = parent
        if (p != null) minecraft.setScreenAndShow(p) else minecraft.gui.setScreen(null)
    }

    companion object {
        private val TITLE = Component.literal("Iustitia — keybind hub")
        private const val WHITE = -1  // opaque 0xFFFFFFFF (see PlayerSearchScreen for the gotcha)
        private val BG = 0xCC101010.toInt()
    }
}