package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.state.level.LevelRenderState
import com.mojang.blaze3d.vertex.PoseStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Level-render suppression mixins: while a chunk-bearing clip is active, the LIVE world's block
 * entities and targeted-block outline are cancelled (the recorded chunk world replaces them).
 *
 * NOTE: the FREECAM spectator no-black-out redirect that used to live here moved to [CameraMixin]
 * in 26.3 — the `LocalPlayer.isSpectator()` consumer it rewrites now lives inside
 * `Camera.extractRenderState` (verified in the 26.3 bytecode), not in `LevelRenderer` at all.
 */
@Mixin(LevelRenderer::class)
class WorldRendererMixin {

    /**
     * MODERN chunk-bearing playclip: suppress the LIVE world's block entities (signs, chests,
     * banners, ...). The recorded chunk world rendered by [dev.iustitia.render.ChunkWorldRenderer]
     * is relocated by `relocOffset` and renders only block *models* — it never renders block
     * entities. So every sign/chest/banner on screen during a chunk playclip is a LIVE block entity
     * at its LIVE block coord, detached from the relocated recorded world (the reported signs bug:
     * sign text floats at `-relocOffset` from the recorded sign block). Cancelling
     * `renderBlockEntities` at HEAD while the chunk gate is active hides them all, so only the
     * recorded chunk world + buffered ghosts show — the block-entity parallel of
     * [SectionRenderStateMixin]'s terrain cancel and [EntityRendererMixin]'s entity cancel, under
     * the exact same gate (`active && chunks != null && !legacyPlayclip`).
     *
     * Recorded block-entity NBT (sign text, chest contents) is a documented v1 follow-up
     * (`ChunkCapture.kt:22-23`); until then chunk playclip shows the recorded block models with no
     * block-entity overlay. The legacy `/ius playclip` + `/ius replay` paths (no chunks) skip this.
     *
     * Target verified against the 26.3 jar: single private method
     * `submitBlockEntities(PoseStack, LevelRenderState, SubmitNodeCollector)` (26.3 renamed the
     * yarn `renderBlockEntities` and the queue parameter became a submit-node collector).
     * `defaultRequire: 1` → a mismatch fails launch; render-time apply is runtime-only-verifiable.
     */
    @Inject(method = ["submitBlockEntities"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveBlockEntities(
        matrices: PoseStack,
        worldRenderState: LevelRenderState,
        collector: SubmitNodeCollector,
        ci: CallbackInfo,
    ) {
        try {
            if (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live block entities drawing (worst case = show-through, never a crash)
        }
    }

    /**
     * Suppress the LIVE world's targeted-block outline (the black wireframe box around the block the
     * local player is looking at) during FREECAM or a MODERN chunk-bearing playclip. In those modes the
     * camera is detached (FREECAM) or the recorded chunk world replaces the live one (MODERN), so the
     * live player's targeted-block outline renders at a LIVE coord that has nothing to do with what the
     * user is spectating — it floats detached in the replay view (the reported "block outlines in the
     * wrong spot" in freecam). Cancelling `renderTargetBlockOutline` at HEAD removes it for that frame.
     *
     * Gate: `freecamActive` (any FREECAM — the camera isn't the live player, so the live target is
     * meaningless) OR the MODERN gate (`active && chunks != null && !legacyPlayclip` — the recorded
     * chunk world is what's on screen, the live target is wrong). The legacy `/ius replay` + FREE view
     * (camera IS the live player, live world on screen) is NOT gated → the outline stays correct there.
     *
     * Target verified against the 26.3 jar: single private method
     * `submitBlockOutline(PoseStack, SubmitNodeCollector, LevelRenderState)` (the 26.3 pipeline
     * submits the outline as a node instead of drawing into an immediate buffer).
     * `defaultRequire: 1` → a signature mismatch fails launch; render-time apply is runtime-only-verifiable.
     */
    @Inject(method = ["submitBlockOutline"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveBlockOutline(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        worldRenderState: LevelRenderState,
        ci: CallbackInfo,
    ) {
        try {
            if (ReplayState.freecamActive ||
                (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip)) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live outline drawing (worst case = a stray box, never a crash)
        }
    }
}