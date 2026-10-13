package dev.iustitia.ui

import dev.iustitia.chathist.ChatHistory
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `/ius chathist panel ...` — a narrow side-panel overlay that reuses the same chrome + render
 * layout as [TranscriptPanelScreen] (right-edge panel, same `0xCC101010` background, same
 * `§8[§diustitia§8]` header, `drawTextWithShadow` rows, `isPauseScreen = false` so the game keeps
 * running) but draws captured chat rows instead of flag events. The panel is a bit wider than the
 * transcript panel (~220px vs ~150px) and wraps long rows to the panel width, so a long message or
 * a long username continues on the next line instead of clipping off the right edge. Refreshed
 * every render via [rowsProvider], so new messages appear live while the panel is open.
 *
 * The row set is supplied as a `() -> List<ChatHistory.Row>` so the panel stays live without the
 * command having to re-open it: each render re-runs the query (a filtered copy of the in-memory
 * per-server bucket — cheap, packet-driven data). [limit] caps how many of the newest rows are
 * drawn (the `pageamount` argument; default 15, matching [TranscriptPanelScreen]'s `take(15)`).
 * Whole body fail-open; a reader error shows "(unavailable)".
 */
class ChatHistPanelScreen(
    private val subtitle: String,
    private val rowsProvider: () -> List<ChatHistory.Row>,
    private val limit: Int,
    private val parent: Screen?,
) : Screen(TITLE) {

    override fun isPauseScreen(): Boolean = false

    override fun init() { try {} catch (_: Throwable) {} }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            val w = this.width
            // A bit wider than the transcript panel (which is ~150px) so usernames + wrapped
            // messages read comfortably; still a narrow right-edge overlay. Wrapping (below)
            // handles anything longer, so this is about comfort, not fitting one line.
            val panelW = minOf(220, w / 4)
            val x0 = w - panelW
            context.fill(x0, 0, w, this.height, BG)
            super.extractRenderState(context, mouseX, mouseY, delta)
            val tr = this.font
            var y = 6
            context.text(tr, Component.literal("§8[§diustitia§8]"), x0 + 4, y, WHITE); y += 11
            context.text(tr, Component.literal("§8chathist"), x0 + 4, y, WHITE); y += 11
            context.text(tr, Component.literal("§f$subtitle"), x0 + 4, y, WHITE); y += 13
            val rows = rowsProvider().take(limit)
            context.text(tr, Component.literal("§8last ${rows.size} msg:"), x0 + 4, y, WHITE); y += 11
            val maxW = panelW - 8
            if (rows.isEmpty()) {
                context.text(tr, Component.literal("§7(no messages)"), x0 + 4, y, WHITE)
            } else {
                for (r in rows) {
                    val ts = try { FMT.format(Date(r.wallClockMs)) } catch (_: Throwable) { "??:??:??" }
                    val full = "§8[$ts] §7${r.name}§r ${r.text}"
                    // Wrap to the panel width so a long message (or a long username) continues on
                    // the next line instead of clipping off the right edge. The timestamp + name
                    // stay on the first wrapped line; continuation lines sit flush-left under them.
                    val wrapped = tr.split(Component.literal(full), maxW)
                    for (ln in wrapped) {
                        if (y > this.height - 12) return
                        context.text(tr, ln, x0 + 4, y, WHITE)
                        y += 11
                    }
                    y += 1 // small gap between messages for readability
                }
            }
        } catch (_: Throwable) {
            try {
                val tr = this.font
                context.text(tr, Component.literal("§7(unavailable)"), 4, 4, WHITE)
            } catch (_: Throwable) {}
        }
    }

    override fun onClose() {
        val p = parent
        if (p != null) minecraft.setScreenAndShow(p) else minecraft.gui.setScreen(null)
    }

    companion object {
        private val TITLE = Component.literal("Iustitia — chathist")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
        private val FMT = SimpleDateFormat("HH:mm:ss")
    }
}