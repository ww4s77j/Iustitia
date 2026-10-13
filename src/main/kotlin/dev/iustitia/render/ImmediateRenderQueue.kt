package dev.iustitia.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.model.Model
import net.minecraft.client.renderer.OrderedSubmitNodeCollector
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.texture.UvMapping
import net.minecraft.client.resources.model.geometry.ItemQuads
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Quaternionf

/**
 * A forwarding [SubmitNodeCollector] for the replay/clip ghost renderer.
 *
 * ## Why this exists
 *
 * In the legacy pipeline this class emitted submissions *immediately* into a
 * `MultiBufferSource`. That class no longer exists in 26.x: everything draws through submit
 * nodes that the game's feature passes consume later in the frame. So the bridge flips
 * direction — instead of draining submissions ourselves, we forward them into the level
 * frame's own collector (handed to us by the level render event context, see
 * [ReplayRenderer]), and vanilla's feature passes draw them like any other entity's nodes.
 *
 * One stable instance is reused across frames; [bind] swaps the target per frame. A missing
 * target falls back to a private [SubmitNodeStorage] so a submission outside an active frame
 * simply accumulates instead of throwing — fail-open, as everywhere in this renderer.
 */
class ImmediateRenderQueue(private var delegate: SubmitNodeCollector? = null) : SubmitNodeCollector {

    private val fallback: SubmitNodeCollector by lazy { SubmitNodeStorage() }
    private val target: SubmitNodeCollector get() = delegate ?: fallback

    /** Re-bind the collector used for this frame (render thread only). */
    fun bind(collector: SubmitNodeCollector?) {
        delegate = collector
    }

    override fun order(priority: Int): OrderedSubmitNodeCollector = target.order(priority)

    override fun submitShadow(matrices: PoseStack, radius: Float, pieces: List<EntityRenderState.ShadowPiece>) =
        target.submitShadow(matrices, radius, pieces)

    override fun submitNameTag(
        matrices: PoseStack,
        pos: Vec3?,
        offsetY: Int,
        text: Component,
        seeThrough: Boolean,
        light: Int,
        cameraState: CameraRenderState,
    ) = target.submitNameTag(matrices, pos, offsetY, text, seeThrough, light, cameraState)

    override fun submitText(
        matrices: PoseStack,
        x: Float,
        y: Float,
        text: FormattedCharSequence,
        dropShadow: Boolean,
        displayMode: Font.DisplayMode,
        light: Int,
        color: Int,
        backgroundColor: Int,
        outlineColor: Int,
    ) = target.submitText(matrices, x, y, text, dropShadow, displayMode, light, color, backgroundColor, outlineColor)

    override fun submitTextBackground(
        matrices: PoseStack,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
        displayMode: Font.DisplayMode,
        light: Int,
    ) = target.submitTextBackground(matrices, x, y, width, height, color, displayMode, light)

    override fun submitFlame(matrices: PoseStack, state: EntityRenderState, rotation: Quaternionf) =
        target.submitFlame(matrices, state, rotation)

    override fun submitLeash(matrices: PoseStack, leashState: EntityRenderState.LeashState) =
        target.submitLeash(matrices, leashState)

    override fun <S : Any> submitModel(
        model: Model<in S>,
        state: S,
        matrices: PoseStack,
        layer: RenderType,
        light: Int,
        overlay: Int,
        color: Int,
        sprite: UvMapping?,
        outlineColor: Int,
    ) = target.submitModel(model, state, matrices, layer, light, overlay, color, sprite, outlineColor)

    override fun <S : Any> submitCrumblingOverlay(
        model: Model<in S>,
        state: S,
        matrices: PoseStack,
        layer: RenderType,
        light: Int,
        overlay: Int,
        color: Int,
        crumbling: ModelFeatureRenderer.CrumblingOverlay,
    ) = target.submitCrumblingOverlay(model, state, matrices, layer, light, overlay, color, crumbling)

    override fun submitMovingBlock(matrices: PoseStack, state: MovingBlockRenderState, outlineColor: Int) =
        target.submitMovingBlock(matrices, state, outlineColor)

    override fun submitBlockModel(
        matrices: PoseStack,
        layer: RenderType,
        parts: List<BlockStateModelPart>,
        tintLayers: IntArray,
        light: Int,
        overlay: Int,
        outlineColor: Int,
    ) = target.submitBlockModel(matrices, layer, parts, tintLayers, light, overlay, outlineColor)

    override fun submitBreakingBlockModel(
        matrices: PoseStack,
        parts: List<BlockStateModelPart>,
        progress: Int,
        hasEmissive: Boolean,
    ) = target.submitBreakingBlockModel(matrices, parts, progress, hasEmissive)

    override fun submitShapeOutline(
        matrices: PoseStack,
        shape: VoxelShape,
        layer: RenderType,
        color: Int,
        lineWidth: Float,
        seeThrough: Boolean,
    ) = target.submitShapeOutline(matrices, shape, layer, color, lineWidth, seeThrough)

    override fun submitItem(
        matrices: PoseStack,
        displayContext: ItemDisplayContext,
        light: Int,
        overlay: Int,
        outlineColor: Int,
        tintLayers: IntArray,
        quads: ItemQuads,
        foilType: ItemStackRenderState.FoilType,
    ) = target.submitItem(matrices, displayContext, light, overlay, outlineColor, tintLayers, quads, foilType)

    override fun submitCustomGeometry(
        matrices: PoseStack,
        layer: RenderType,
        renderer: SubmitNodeCollector.CustomGeometryRenderer,
    ) = target.submitCustomGeometry(matrices, layer, renderer)

    override fun submitQuadParticleGroup(state: QuadParticleRenderState) =
        target.submitQuadParticleGroup(state)

    override fun submitGizmoPrimitives(
        group: DrawableGizmoPrimitives.Group,
        cameraState: CameraRenderState,
        seeThrough: Boolean,
    ) = target.submitGizmoPrimitives(group, cameraState, seeThrough)

    private companion object {
        @Suppress("unused")
        val client: Minecraft? get() = try { Minecraft.getInstance() } catch (_: Throwable) { null }
    }
}
