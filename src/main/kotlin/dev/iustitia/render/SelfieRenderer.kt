package dev.iustitia.render

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.util.function.Consumer

/**
 * Captures the offender-selfie framebuffer (Phase B). Registers `WorldRenderEvents.END_MAIN`,
 * which fires on the render thread at the END of the main world render pass — i.e. in the SAME
 * frame [dev.iustitia.mixin.CameraMixin] repointed the camera at the offender, and BEFORE the HUD
 * is drawn, so the screenshot is a clean world view of the offender with no hotbar/chat overlay.
 *
 * On a frame where [OffenderCapture.frameActive] is raised, this screenshots the main framebuffer
 * to the armed request's `snapshots/<tick>_<name>.png` and echoes the vanilla "saved / error"
 * [net.minecraft.network.chat.Component] to chat (clickable file link). Then it clears the capture state so the
 * camera reverts next frame. Fail-open throughout; the `finally` clear is what enforces the
 * single-frame guarantee even if the GL read throws.
 *
 * Runtime-only-verifiable: a build cannot confirm `END_MAIN` fires after the world render holds the
 * offender view, nor that the framebuffer is readable here. If PNGs come out blank/black, the
 * capture point may need to move to `AFTER_ENTITIES`/`BEFORE_TRANSLUCENT`.
 */
object SelfieRenderer {

    fun register() {
        try {
            // 26.3 replacement for WorldRenderEvents.END_MAIN (same late-frame timing intent;
            // exact phase semantics are runtime-verifiable).
            LevelRenderEvents.END_MAIN.register(LevelRenderEvents.EndMain { _ ->
                try {
                    val req = OffenderCapture.activeReq
                    if (req == null || !OffenderCapture.frameActive) return@EndMain
                    val mc = Minecraft.getInstance()
                    val fb = mc.gameRenderer.mainRenderTarget() ?: return@EndMain
                    Screenshot.grab(
                        req.dir, req.file, fb, 1,
                        Consumer { msg -> try { mc.gui.hud.chat.addClientSystemMessage(msg) } catch (_: Throwable) {} }
                    )
                } catch (_: Throwable) {
                    // The finally clears state so the camera can't stay overridden.
                } finally {
                    OffenderCapture.frameActive = false
                    OffenderCapture.activeReq = null
                }
            })
        } catch (_: Throwable) {}
    }
}