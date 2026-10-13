package dev.iustitia.alert

import dev.iustitia.history.FlagHistory
import dev.iustitia.info.CheckInfo
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import java.util.UUID
import kotlin.math.ceil

/**
 * Renders the fixed alert layout:
 *   §8[§diustitia§8] §f(Name) §<sev>(Check) §<sev>(VL)
 * Severity color scales with the violation ratio (vl / setbackVL):
 *   < 2× → §e (yellow), < 3× → §6 (orange), >= 3× → §c (red).
 * The violation count is shown as the integer ceiling of the VL.
 *
 * The line is also self-documenting: hovering shows what the check detects + this player's
 * session alert count + the severity legend, and clicking runs `/ius hist <name>` to open that
 * player's flag history. Both are client-side chat-component events (no packet is sent). All
 * enrichment is fail-open — if the history/info lookup fails we still return the plain alert.
 */
object AlertFormatter {

    /** Severity band 0/1/2 (yellow / orange / red) from the vl/setbackVL ratio. 2 = ≥3×, 1 = ≥2×. */
    fun band(vl: Double, setbackVL: Double): Int =
        if (vl >= 3.0 * setbackVL) 2 else if (vl >= 2.0 * setbackVL) 1 else 0

    fun sevChar(b: Int): Char = when (b) { 2 -> 'c'; 1 -> '6'; else -> 'e' }

    fun format(name: String, check: String, vl: Double, setbackVL: Double, uuid: UUID, checkId: String): Component {
        val sev = sevChar(band(vl, setbackVL))
        val count = ceil(vl).toInt().coerceAtLeast(1)
        val raw = buildString {
            append("§8[§diustitia§8] §f")
            append(name)
            append(" §")
            append(sev)
            append('(')
            append(check)
            append(") §")
            append(sev)
            append('(')
            append(count)
            append(')')
        }
        val base = Component.literal(raw)
        return try {
            val hover = buildHover(uuid, checkId, check)
            val click = ClickEvent.RunCommand("/ius hist $name")
            base.withStyle { s: Style ->
                s.withClickEvent(click).withHoverEvent(HoverEvent.ShowText(hover))
            }
        } catch (_: Throwable) {
            // fail-open: if enrichment fails, return the plain alert with no interactivity
            base
        }
    }

    /**
     * Collapsed batch line for the smart-batching flush: `§8[iustitia§8] §f<name> §<sev>(Reach ×4,
     * Backtrack ×2) §7(last 5s)`. [parts] are the per-check "label ×count" strings; [worstBand]
     * drives the severity color. [lag] prefixes `§7[lag] ` when softened during a server-lag burst.
     * [compact] drops the hover tail. Click → `/ius hist <name>`.
     */
    fun formatBatch(
        name: String, parts: List<String>, worstBand: Int, windowSec: Int,
        lag: Boolean, compact: Boolean, uuid: UUID,
    ): Component {
        val sev = sevChar(worstBand)
        val raw = buildString {
            if (lag) append("§7[lag] ")
            append("§8[§diustitia§8] §f")
            append(name)
            append(" §")
            append(sev)
            append('(')
            append(parts.joinToString(", "))
            append(") §7(last ")
            append(windowSec)
            append("s)")
        }
        val base = Component.literal(raw)
        if (compact) return base
        return try {
            val hover = Component.empty()
                .append(Component.literal("§7${FlagHistory.confidenceExplanation(uuid)}"))
                .append(Component.literal("\n§7click for flag history"))
            base.withStyle { s: Style ->
                s.withClickEvent(ClickEvent.RunCommand("/ius hist $name")).withHoverEvent(HoverEvent.ShowText(hover))
            }
        } catch (_: Throwable) { base }
    }

    /** Compact one-line alert (no hover tail, no severity bar) for [IustitiaConfig.compactMode]. */
    fun formatCompact(name: String, check: String, vl: Double, setbackVL: Double, uuid: UUID, checkId: String): Component {
        val sev = sevChar(band(vl, setbackVL))
        val count = ceil(vl).toInt().coerceAtLeast(1)
        val raw = buildString {
            append("§8[§diustitia§8] §f")
            append(name)
            append(" §")
            append(sev)
            append(check)
            append(" §7vl")
            append(count)
        }
        val base = Component.literal(raw)
        return try {
            base.withStyle { s: Style -> s.withClickEvent(ClickEvent.RunCommand("/ius hist $name")) }
        } catch (_: Throwable) { base }
    }

    private fun buildHover(uuid: UUID, checkId: String, checkLabel: String): Component {
        val lines = Component.empty()
        lines.append(Component.literal("§7${CheckInfo.describe(checkId)}"))
        try {
            val alerts = FlagHistory.sessionAlertCount(uuid)
            val checkFlags = FlagHistory.flagsForCheck(uuid, checkId).size
            lines.append(Component.literal("\n§7alerts: §f$alerts §7| §7$checkLabel flags: §f$checkFlags"))
        } catch (_: Throwable) {
            // counts unavailable — skip the line
        }
        // Human "why" line: the last evidenced flag for this check, read as a phrase so the
        // hover explains the specific reading behind the alert (e.g. "hit from 4.20 blocks
        // (vanilla max 3.0)"). Prefers the flag's human `extra` phrase; falls back to the
        // sub-label + measurement/threshold. A check's flags share one evidence shape, so the
        // most recent evidenced flag is the representative reading. Fail-open — skip on miss.
        try {
            val ev = FlagHistory.flagsForCheck(uuid, checkId).lastOrNull { it.evidence != null }?.evidence
            if (ev != null) {
                val why = ev.extra
                    ?: listOfNotNull(ev.subLabel,
                        if (ev.measurement != null || ev.threshold != null)
                            "${ev.measurement ?: "?"}/${ev.threshold ?: "?"}" else null)
                      .joinToString(" · ")
                if (why.isNotEmpty()) lines.append(Component.literal("\n§bwhy §7$why"))
            }
        } catch (_: Throwable) {}
        // FP-cause hint (Phase 2 #18 text version): one benign-cause line so users learn when not
        // to hackusate. The standalone HUD note is a deferred Phase B render piece.
        try { dev.iustitia.info.FpHint.hint(checkId)?.let { lines.append(Component.literal("\n§8FP note: §7$it")) } } catch (_: Throwable) {}
        try { lines.append(Component.literal("\n§7${FlagHistory.confidenceExplanation(uuid)}")) } catch (_: Throwable) {}
        lines.append(Component.literal("\n${CheckInfo.SEVERITY_LEGEND}"))
        return lines
    }
}