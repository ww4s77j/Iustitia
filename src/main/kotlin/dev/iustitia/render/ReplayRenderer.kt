package dev.iustitia.render

import dev.iustitia.NumFmt
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.mixin.LivingEntityRendererAccessor
import dev.iustitia.replay.ReplayBuffer
import dev.iustitia.replay.ReplayState
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.level.block.Blocks
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.EntityModel
import net.minecraft.client.renderer.entity.state.BoatRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.entity.state.MinecartRenderState
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntitySpawnReason
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import com.mojang.math.Axis
import org.joml.Matrix4f
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Instant-replay renderer (Phase 2): while [ReplayState] is active, draws a HUMANOID "ghost" of every
 * tracked player at the playhead frame's buffered position — head, torso, arms and legs as
 * pose-aware boxes (the vanilla player is itself a box model), advanced through the timeline at the
 * replay speed. One shared renderer serves `/ius replay` (a live-buffer window) and `/ius playclip`
 * (a `.iusclip` loaded into [ReplayState]).
 *
 * ## Why a humanoid model, not a single box
 *
 * The first cut drew one wireframe box per player; it read as an anonymous gray crate and you
 * couldn't tell which ghost was which user. This draws the player's actual limb breakdown so a ghost
 * reads as a person (and the pose — standing / sneaking / gliding / swimming / riptide — is visible),
 * colors each ghost by that player's cheat tier (green / yellow / red), highlights the replay focus
 * in cyan with a thicker outline + a `▶` name marker, and floats a name tag above every ghost so you
 * can read who is who. The model is drawn with the same runtime-proven
 * [ShapeRenderer.drawOutline] path as the target-highlight overlay (depth-tested →
 * occluded by walls, no wallhack); only the boxes+labels are new.
 *
 * ## In-world ghosts
 *
 * `WorldRenderEvents.AFTER_ENTITIES` (the canonical world-space overlay phase). The view matrix
 * there is camera-rotation-only, so we translate by `-cameraPos` once and draw each ghost in world
 * coords. Color alpha is baked into the ARGB int's high byte (`drawOutline` passes its `int`
 * straight to `VertexConsumer.color(int)`; its `float` is line WIDTH, not alpha). Pose handling:
 * sneak lowers the model ~0.12 blocks; glide/swim/riptide lay it 90° forward (the body tips onto its
 * facing axis). The per-player facing nub is computed directly from the buffered yaw (guaranteed
 * correct regardless of any matrix-rotation sign ambiguity). Display-only — adds NO detection.
 *
 * ## Name tags
 *
 * A billboarded name floats above each ghost via [Font.draw] with `TextLayerType.SEE_THROUGH`
 * (always readable, even through terrain). The text is drawn with the view-rotation matrix (camera
 * rotation) after translating to the ghost's world point, which makes it screen-aligned/upright —
 * the same property that lets vanilla entity labels face the camera. Mild distance scaling keeps
 * near ghosts from blowing up and far ghosts readable. Fail-open per ghost: one bad snap never
 * kills the frame.
 *
 * ## "Rewind feel"
 *
 * [ReplayState.hideLive] (default on) hides every live OTHER player during a replay via
 * [dev.iustitia.mixin.EntityRendererMixin], so only the buffered ghosts render — a rewind-the-world
 * feel. The live game + detection keep running; rendering snaps back the instant the replay stops.
 *
 * ## Replay HUD
 *
 * A bottom-center banner (via the stable [HudRenderCallback]) shows the focus name + speed + a
 * progress bar, so a 20–40s replay has a visible "where am I in the rewind".
 *
 * Runtime-only-verifiable: a build confirms the model compiles + the matrices/colors match the
 * established overlay pattern; whether ghosts land exactly on the world, the lay-pose rotation
 * direction, and the name-tag orientation are confirmed only at runtime (all wrapped in fail-open).
 */
object ReplayRenderer {

    // Limb dimensions (blocks). Feet at origin, +Y up, +Z = body front.
    private const val LEG_H = 0.72
    private const val TORSO_H = 0.72
    private const val ARM_H = 0.72
    private const val HEAD = 0.4
    private const val HALF_W = 0.0625        // limb half-width
    private const val LIMB_D = 0.0625        // limb half-depth
    private const val TORSO_HALF = 0.25      // torso half-width (shoulders)
    private const val TORSO_HALF_D = 0.0625 // torso half-depth
    private const val LEG_OFF = 0.075        // leg center X offset from centerline
    private const val ARM_OFF = 0.3125      // arm center X offset (just outside torso)

    // Bright, full-alpha ARGB tier colors (drop the unreadable gray). Alpha in the high byte.
    private val GREEN_COLOR = 0xFF40E060.toInt()
    private val YELLOW_COLOR = 0xFFFFD020.toInt()
    private val RED_COLOR = 0xFFFF4040.toInt()
    private val FOCUS_COLOR = 0xFF40D0FF.toInt()   // cyan — distinct from all three tier colors

    private const val GHOST_WIDTH = 1.5f
    private const val FOCUS_WIDTH = 2.5f

    /** Non-player entity ghosts (v13) are only drawn within this radius (blocks) of the camera, so a
     *  big captured scene doesn't submit hundreds of far entity models per frame. */
    private const val ENTITY_RENDER_RADIUS = 64.0
    private const val ENTITY_RENDER_RADIUS_SQ = ENTITY_RENDER_RADIUS * ENTITY_RENDER_RADIUS

    // Fullbright light coord for see-through text (LightTexture.MAX_LIGHT_COORDINATE).
    private const val FULL_LIGHT = 0xF000F0

    // Health-indicator smoothing: the displayed number lerps toward the raw synced target by this
    // fraction per PLAYHEAD tick (geometric — 0.3/tick converges to ~94% in ~8 ticks). The server's
    // other-player HEALTH tracked-data is coarse/batched and decoupled from the hurt flash (a +15
    // heal batch, a -16 crit, delivered out of order — the health-sync investigation traced the wild
    // 3→18→7→17 swings are genuine server values, not a pipeline bug), so snapping the indicator to
    // the raw value flickers the number across consecutive ticks. Smoothing ramps a batched jump
    // visibly instead. Matches the WalkState limb-amp lerp rate at :632. Seek/scrub/rewind snaps.
    private const val HEALTH_SMOOTH_RATE = 0.3f

    fun register() {
        try {
            // COLLECT_SUBMITS fires while the level frame's submit nodes are being gathered: our
            // ghost submissions land in the same frame and vanilla's feature passes draw them —
            // the 26.x replacement for the legacy AFTER_ENTITIES direct-draw window.
            LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { ctx ->
                try {
                    if (!ReplayState.active) return@CollectSubmits
                    drawGhosts(ctx)
                } catch (_: Throwable) {
                    // fail-open: a render error never crashes the client
                }
            })
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("iustitia", "replay_hud")) { context, _ ->
                try { if (ReplayState.active) renderHud(context) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun drawGhosts(ctx: LevelRenderContext) {
        val mc = Minecraft.getInstance()
        // Partial-tick progress toward the next client tick. Consumed by the playhead lerp below
        // (vanilla's own entity pass lerps with the same value).
        val tickDelta = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        val frame = ReplayState.currentFrameLerped(tickDelta) ?: return
        // Draw when EITHER ghost set is non-empty: a frame whose tracked players have all logged off can
        // still carry captured mobs/vehicles (SnapClip short-circuits on empty snaps only, which drops
        // those entity ghosts).
        if (frame.snaps.isEmpty() && frame.entities.isEmpty()) return
        val camState = ctx.levelState().cameraRenderState
        val camPos = camState.pos
        // Camera data comes straight from the frame's own CameraRenderState (vanilla fills exactly
        // one per frame and every entity reads it) — no local refresh step anymore.
        // The AFTER_ENTITIES modelview carries the CAMERA ROTATION (the view matrix; the camera
        // position is NOT on it, so we translate by -camPos below). A name tag drawn with no
        // counter-rotation inherits that view rotation and renders edge-on / back-facing → invisible.
        // We billboard each tag the way vanilla `EntityRenderer.renderLabelIfPresent` does: multiply
        // by the camera's orientation quaternion (`Camera.getRotation` — the same value vanilla stores
        // as `CameraRenderState.orientation`), which is the inverse of the view rotation already on
        // the stack, leaving the tag screen-aligned (cancels yaw AND pitch). The old yaw-only
        // `180f - cameraYaw` billboard only cancels yaw, so it left the tag tilted out of view whenever
        // the camera pitched and double-rotated it the rest of the time → nametags 100% invisible.
        // scale(scale,-scale,scale) mirrors vanilla's (0.025,-0.025,0.025): −Y flips glyphs upright
        // (glyph +Y is down), +X keeps them L→R toward the camera (vanilla labels read correctly at +X).
        val camRot: org.joml.Quaternionf? = camState.orientation
        val matrices = ctx.poseStack()
        // The frame's own submit-node collector: ghost model/text/outline submissions land here and
        // vanilla's feature passes draw them with the rest of the frame (the 26.x replacement for the
        // legacy immediate MultiBufferSource bridge). Fail-open: no collector → nothing to submit into,
        // skip this frame's ghosts rather than crashing.
        val collector: SubmitNodeCollector = try { ctx.submitNodeCollector() } catch (_: Throwable) { return }
        val tr = mc.font
        val focusUuid = ReplayState.focusUuid
        // In POV the camera sits at the focus ghost's eye — don't draw that ghost (you're inside its
        // head; rendering it would fill the view with a wireframe/model). Other ghosts still render.
        val skipFocus = ReplayState.cameraMode == ReplayState.CameraMode.POV && focusUuid != null
        // Active-alert labels for this frame: uuid → the offender's alert label, for any alert whose
        // captured tick is within ±1 of the playhead frame (so a ⚠ floats over a ghost exactly as the
        // playhead crosses the moment the cheat fired). Fail-open: an empty/missing map just draws none.
        val nowTick = frame.tick
        val alertLabelFor: Map<java.util.UUID, String> = try {
            // Cache per tick (frame.tick changes once per replay tick, not per render frame); the
            // common no-alerts case short-circuits to an empty map without allocating a filter.
            if (nowTick == cachedAlertTick) cachedAlertLabels
            else {
                val built: Map<java.util.UUID, String> =
                    if (ReplayState.alerts().isEmpty()) emptyMap()
                    else ReplayState.alerts().filter { kotlin.math.abs(it.tick - nowTick) <= 1 }
                        .associate { it.uuid() to it.label }
                cachedAlertTick = nowTick
                cachedAlertLabels = built
                built
            }
        } catch (_: Throwable) { emptyMap() }
        matrices.pushPose()
        // Fold the relocation offset into the single shared origin translate: every per-snap
        // translate(s.x, s.y, s.z) below then lands the ghost at (recordedPos + offset) - camPos, i.e.
        // the whole scene shifts as a rigid block to the user (focus player's start → the user). The
        // terrain overlay draws in this same frame, so its absolute-coord quads relocate by the same
        // offset. null offset ⇒ legacy absolute rendering (relocate disabled / unavailable).
        val o = ReplayState.relocOffset ?: net.minecraft.world.phys.Vec3.ZERO
        matrices.translate(-camPos.x + o.x, -camPos.y + o.y, -camPos.z + o.z)
        try {
            // Chunk world (v6+ clip-only; null for /ius replay + pre-v6 clips). Solid, textured blocks
            // — the real captured map, face-culled to the surface shell — so the user can free-spectate
            // anywhere, including underground. Drawn first (under ghosts + the v5 wireframe terrain).
            // Skipped for a Legacy playclip (v1.1.0 = ghosts over the live world, no chunk world) —
            // belt-and-suspenders with ReplayState.start nulling chunks for Legacy.
            if (!ReplayState.legacyPlayclip) {
                try { ChunkWorldRenderer.render(matrices, collector, camPos, o) } catch (_: Throwable) {}
            }
            // Terrain shell (clip-only; null for /ius replay). Face-culled, so only the visible
            // surface blocks draw — fully-enclosed blocks contribute nothing. Fail-open per frame.
            // Also skipped for a Legacy playclip (no terrain in v1.1.0).
            if (!ReplayState.legacyPlayclip) {
                try { TerrainOverlay.render(matrices, collector) } catch (_: Throwable) {}
            }
            for (s in frame.snaps) {
                try {
                    if (skipFocus && s.uuid() == focusUuid) continue
                    drawHumanoid(matrices, collector, tr, s, focusUuid, camPos, o, camRot, alertLabelFor[s.uuid()], nowTick)
                } catch (_: Throwable) {
                    // skip one bad ghost, keep the rest
                }
            }
            // v13 non-player entity ghosts (mobs / animals / boats / minecarts) — the rest of the
            // captured scene, drawn with each entity's vanilla renderer model (no tier color: a mob
            // isn't flagged). Gated by clipEntities; fail-open per entity. Drawn after the players so
            // the two ghost sets share the one relocation translate above.
            try {
                val cfg = try { ConfigManager.config } catch (_: Throwable) { null }
                // Skipped for a Legacy playclip: v1.1.0 was ghosts-over-the-live-world, with no captured
                // scene — belt-and-suspenders with the chunks/terrain gates above.
                if (cfg?.clipEntities == true && !ReplayState.legacyPlayclip && frame.entities.isNotEmpty()) {
                    for (e in frame.entities) {
                        try {
                            if (entityRendererFor(e.typeId) != null) {
                                drawEntityGhost(matrices, collector, e, camPos, o, frame.tick)
                            } else if (nonLivingRendererFor(e.typeId) != null) {
                                drawNonLivingEntityGhost(matrices, collector, camState, e, camPos, o, frame.tick)
                            }
                        } catch (_: Throwable) {
                            // skip one bad entity, keep the rest
                        }
                    }
                }
            } catch (_: Throwable) {}
            // NOTE: name tags / alerts are submitted into the frame's collector (see drawHumanoid), so
            // vanilla's own feature passes draw them on top of the block/model geometry — no manual
            // flush step is needed anymore. Fall through to the pop.
        } finally {
            matrices.popPose()
        }
    }

    /**
     * Draw one ghost: a pose-aware humanoid wireframe + a facing nub (direct, correct yaw) + a
     * billboarded name tag. [camPos] is only used for the name-tag distance scaling. [alertLabel], when
     * non-null, floats a ⚠ marker above the ghost (the playhead just crossed an alert for this player).
     */
    private fun drawHumanoid(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        tr: Font,
        s: ReplayBuffer.PlayerSnap,
        focusUuid: java.util.UUID?,
        camPos: net.minecraft.world.phys.Vec3,
        offset: net.minecraft.world.phys.Vec3,
        camRot: org.joml.Quaternionf?,
        alertLabel: String?,
        tick: Int,
    ) {
        // Offset floats for the name-tag / alert-marker distance scaling — the apparent distance must
        // be to the RELOCATED ghost (recorded + offset), not the original recorded spot (which on a
        // cross-server playclip could be far away and would blow the tag up to max scale).
        val oxF = offset.x.toFloat()
        val oyF = offset.y.toFloat()
        val ozF = offset.z.toFloat()
        val uuid = s.uuid()
        val isFocus = focusUuid != null && uuid == focusUuid
        val tier = try { FlagHistory.tierFor(uuid) } catch (_: Throwable) { FlagHistory.Tier.GREEN }
        val color = if (isFocus) FOCUS_COLOR else tierColor(tier)
        val width = if (isFocus) FOCUS_WIDTH else GHOST_WIDTH
        val laid = s.pose == ReplayBuffer.POSE_GLIDE || s.pose == ReplayBuffer.POSE_SWIM || s.pose == ReplayBuffer.POSE_RIPTIDE
        // v7 clip overlays (gated, default off): a numeric health indicator + a totem-pop counter badge
        // on the nametag. Fail-open off. Both are Modern-playclip-only conceptually but the gate is the
        // config flag (Legacy has no chunk world but ghosts + nametags still render, so the indicator
        // still applies to a Legacy replay too — harmless; the toggle is the source of truth).
        val cfg = try { ConfigManager.config } catch (_: Throwable) { null }
        val healthOn = try { cfg?.clipHealthIndicator == true } catch (_: Throwable) { false }
        val totemOn = try { cfg?.clipTotemPopCounter == true } catch (_: Throwable) { false }
        // Per-ghost totem-pop count within the clip window (Phase 4: ReplayState aggregates the clip's
        // totem events). 0 when the clip has no totems / the accessor is unavailable (pre-v7 clip).
        val totemCount = if (totemOn) try { ReplayState.totemCountFor(uuid) } catch (_: Throwable) { 0 } else 0

        // --- real player model path (experimental, default off): drive vanilla's AvatarRenderer
        //     model with a fabricated render state + the default Steve skin (no skin fetch / no
        //     network). If it draws, skip the box outline. If it returns false (toggle off, renderer
        //     unavailable, or any throw) the box outline below is the fail-open fallback. ---
        val drewModel = drawModel(matrices, collector, s, tick)
        if (!drewModel) {
            // --- body (feet at the world point; pose applied to the local frame) ---
            matrices.pushPose()
            try {
                matrices.translate(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
                if (s.pose == ReplayBuffer.POSE_SNEAK) matrices.translate(0.0, -0.12, 0.0)
                if (laid) matrices.mulPose(Matrix4f().rotation(Axis.XP.rotationDegrees(90f)))
                // legs
                box(matrices, collector,-LEG_OFF - HALF_W, 0.0, -LIMB_D, -LEG_OFF + HALF_W, LEG_H, LIMB_D, color, width)
                box(matrices, collector, LEG_OFF - HALF_W, 0.0, -LIMB_D,  LEG_OFF + HALF_W, LEG_H, LIMB_D, color, width)
                // torso (sits on the legs)
                box(matrices, collector,-TORSO_HALF, LEG_H, -TORSO_HALF_D, TORSO_HALF, LEG_H + TORSO_H, TORSO_HALF_D, color, width)
                // arms (hang at the torso sides)
                box(matrices, collector,-ARM_OFF - HALF_W, LEG_H, -LIMB_D, -ARM_OFF + HALF_W, LEG_H + ARM_H, LIMB_D, color, width)
                box(matrices, collector, ARM_OFF - HALF_W, LEG_H, -LIMB_D,  ARM_OFF + HALF_W, LEG_H + ARM_H, LIMB_D, color, width)
                // head (on top of the torso) — tilted by the buffered pitch around the neck pivot (top
                // of the torso) so the ghost nods to where the player was looking. Rotation sign is
                // runtime-verifiable; on any throw we draw it un-tilted (fail-open).
                val headY = LEG_H + TORSO_H
                val hh = HEAD / 2
                try {
                    matrices.pushPose()
                    matrices.translate(0.0, headY.toDouble(), 0.0)
                    matrices.mulPose(Matrix4f().rotation(Axis.XP.rotationDegrees(s.pitch)))
                    box(matrices, collector,-hh, 0.0, -hh, hh, HEAD.toDouble(), hh, color, width)
                } catch (_: Throwable) {
                    box(matrices, collector,-hh, headY, -hh, hh, headY + HEAD, hh, color, width)
                } finally {
                    matrices.popPose()
                }
            } finally {
                matrices.popPose()
            }

            // --- facing nub: a small cube ahead of the head along the buffered LOOK ray (yaw + pitch)
            //     so the indicator always points where the player was looking (independent of the body
            //     lay-rotation above). Computed directly from yaw+pitch in world coords. ---
            try {
                val yawRad = s.yaw * (PI / 180.0)
                val pitchRad = s.pitch * (PI / 180.0)
                val fx = -sin(yawRad)
                val fz = cos(yawRad)
                val cp = cos(pitchRad)
                val sp = sin(pitchRad)
                val nubY = if (laid) LEG_H + TORSO_H * 0.5 else LEG_H + TORSO_H + HEAD * 0.5
                val reach = 0.45
                val nx = s.x + fx * cp * reach
                val ny = s.y + nubY - sp * reach   // pitch+ (looking down) → nub drops
                val nz = s.z + fz * cp * reach
                val r = 0.07
                box(matrices, collector,nx - r, ny - r, nz - r, nx + r, ny + r, nz + r, color, width)
            } catch (_: Throwable) {}
        }

        // --- name tag (billboarded, see-through) ---
        try {
            val name = s.name
            // v7 totem-pop counter badge: a gold ⚡<count> appended before the name when the toggle is
            // on and this ghost popped ≥1 totem in the clip window. Pure text — no feature renderer.
            val totemBadge = if (totemOn && totemCount > 0) "§6⚡$totemCount§r " else ""
            val label = if (isFocus) Component.literal("§f▶ $totemBadge$name") else Component.literal("$totemBadge$name")
            // labelY = the text's TOP in world space (after the −Y flip the glyphs extend DOWNWARD
            // from this point). The model + hat reach ~1.95 above the feet, so the baseline must sit
            // high enough that the text's bottom (labelY − ~0.25) clears the head — otherwise the tag
            // renders inside the model. Bumped up from 2.05/1.7/0.5 for that clearance.
            val labelY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
            matrices.pushPose()
            try {
                matrices.translate(s.x.toDouble(), (s.y + labelY).toDouble(), s.z.toDouble())
                val dx = (s.x + oxF) - camPos.x.toFloat()
                val dy = (s.y + labelY + oyF) - camPos.y.toFloat()
                val dz = (s.z + ozF) - camPos.z.toFloat()
                val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                // Billboard via the camera orientation quaternion (see drawGhosts) — cancels the view
                // rotation already on the stack so the tag is screen-aligned (yaw + pitch), like vanilla.
                if (camRot != null) matrices.mulPose(Matrix4f().rotation(camRot))
                matrices.scale(scale, -scale, scale)
                val w = tr.width(label)
                val textColor = if (isFocus) FOCUS_COLOR else color
                collector.submitText(
                    matrices, -w / 2f, 0f, label.visualOrderText, false,
                    Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, textColor, 0, 0,
                )
            } finally {
                matrices.popPose()
            }
        } catch (_: Throwable) {}

        // --- v7 health indicator (billboarded, see-through): a numeric §c<hp>§r/§f<max> line above
        //     the name, plus a transient §c-<dmg> popup for ~40 ticks after a detected health drop
        //     (damage amount = health-diff; attack SOURCE is not available client-side for other
        //     players). Gated by clipHealthIndicator; fail-open per ghost. ---
        if (healthOn) {
            try {
                val hs = healthState.computeIfAbsent(uuid) { HealthState() }
                // Drop detection runs on the RAW target (s.health), not the smoothed display — a real
                // damage event pops -dmg immediately even while the displayed number is still ramping
                // down. The server's batched health sync is decoupled from the hurt flash, so a single
                // recorded -16 is a real hit worth flagging regardless of when the smoothed number
                // reaches it.
                val prev = hs.lastHealth
                if (!prev.isNaN() && s.health < prev - 0.5f) {
                    hs.dmgAmount = (prev - s.health).coerceIn(0.5f, 40f)
                    hs.dmgTick = tick
                }
                hs.lastHealth = s.health
                // Smoothed display: lerp the shown number toward the raw target once per PLAYHEAD TICK
                // (tickJump == 1), mirroring the WalkState limb-amp lerp at :622/:632 — FPS-independent
                // (the playhead holds for ~3 render frames between ticks). A 0.3/tick geometric lerp
                // converges in ~8 ticks, so the server's coarse/batched HEALTH sync (a +15 heal batch,
                // a -16 crit, delivered decoupled from the hurt flash — the "nonsensical swing" the
                // health-sync investigation traced to coarse server sync, not a pipeline bug) ramps visibly
                // instead of snapping the number 3→18→7 across three consecutive ticks. Seek / scrub /
                // rewind / first sighting (tickJump != 1) snaps to target so a timeline jump doesn't
                // ramp; tickJump == 0 (same frame redrawn) holds the smoothed value (no per-frame step).
                val tickJump = tick - hs.displayTick
                when {
                    hs.displayHealth.isNaN() -> hs.displayHealth = s.health
                    tickJump == 1 -> hs.displayHealth += (s.health - hs.displayHealth) * HEALTH_SMOOTH_RATE
                    tickJump == 0 -> { }  // same playhead frame redrawn → hold
                    else -> hs.displayHealth = s.health   // seek / scrub / rewind
                }
                hs.displayTick = tick
                val maxH = if (s.maxHealth > 0f) s.maxHealth else 20f
                val hp = hs.displayHealth.coerceIn(0f, maxH)
                val baseY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
                val hy = baseY + 0.35f
                matrices.pushPose()
                try {
                    matrices.translate(s.x.toDouble(), (s.y + hy).toDouble(), s.z.toDouble())
                    val dx = (s.x + oxF) - camPos.x.toFloat()
                    val dy = (s.y + hy + oyF) - camPos.y.toFloat()
                    val dz = (s.z + ozF) - camPos.z.toFloat()
                    val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                    if (camRot != null) matrices.mulPose(Matrix4f().rotation(camRot))
                    matrices.scale(scale, -scale, scale)
                    val hpInt = (hp + 0.5f).toInt()
                    val maxInt = (maxH + 0.5f).toInt()
                    val hpLabel = Component.literal("§c$hpInt§r/§f$maxInt")
                    val w = tr.width(hpLabel)
                    collector.submitText(
                        matrices, -w / 2f, 0f, hpLabel.visualOrderText, false,
                        Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, WHITE, 0, 0,
                    )
                    if (tick - hs.dmgTick in 0..40 && hs.dmgAmount > 0f) {
                        val dmgInt = (hs.dmgAmount + 0.5f).toInt()
                        val popup = Component.literal("§c-$dmgInt")
                        val pw = tr.width(popup)
                        collector.submitText(
                            matrices, w / 2f + 2f, 0f, popup.visualOrderText, false,
                            Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, RED_COLOR, 0, 0,
                        )
                    }
                } finally {
                    matrices.popPose()
                }
            } catch (_: Throwable) {}
        }

        // --- alert marker: when the playhead crosses an alert for this player, float a red ⚠ label
        //     above the name tag (and above the health line when it's shown) so the moment a cheat
        //     fired is visible on the ghost itself ---
        if (alertLabel != null) {
            try {
                val alert = Component.literal("§c⚠ §f$alertLabel")
                val baseY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
                val ay = baseY + if (healthOn) 0.7f else 0.35f
                matrices.pushPose()
                try {
                    matrices.translate(s.x.toDouble(), (s.y + ay).toDouble(), s.z.toDouble())
                    val dx = (s.x + oxF) - camPos.x.toFloat()
                    val dy = (s.y + ay + oyF) - camPos.y.toFloat()
                    val dz = (s.z + ozF) - camPos.z.toFloat()
                    val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                    if (camRot != null) matrices.mulPose(Matrix4f().rotation(camRot))
                    matrices.scale(scale, -scale, scale)
                    val w = tr.width(alert)
                    collector.submitText(
                        matrices, -w / 2f, 0f, alert.visualOrderText, false,
                        Font.DisplayMode.SEE_THROUGH, FULL_LIGHT, RED_COLOR, 0, 0,
                    )
                } finally {
                    matrices.popPose()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Real-player-model path (gated by [IustitiaConfig.replayPlayerModels], default on).
     *
     * Drives vanilla's [AvatarRenderer] model with a hand-built [net.minecraft.client.renderer.entity.state.AvatarRenderState]
     * and the player's REAL skin ([dev.iustitia.render.ReplaySkins.resolve] — a per-UUID cache that reads
     * the tab-list [net.minecraft.client.multiplayer.PlayerInfo] vanilla already keeps warm; Steve/Alex
     * fallback for players not in the tab list), drawn via [net.minecraft.client.model.Model.render]
     * straight onto the overlay's [MultiBufferSource]. This bypasses the 1.21.11
     * `SubmitNodeCollector` entity pipeline (which the world overlay context can't supply) by
     * going one level lower — the base `Model.render` still takes a plain `VertexConsumer`, exactly what
     * `ctx.consumers()` exposes (the same path the GUI skin renderer uses).
     *
     * The renderer is resolved from the local player (`mc.getEntityRenderDispatcher().getRenderer(mc.player)`)
     * — same renderer vanilla uses for every other client player, so no fabricated entity is needed. The
     * state is populated minimally for a posed, facing player at the buffered yaw + pitch (head tilts to
     * the look direction); the model's `setAngles` poses the limbs and we apply the sneak/glide body
     * transforms on the matrix (mirroring the box path).
     *
     * Returns true if the model was drawn (caller skips the box outline); false (toggle off, renderer
     * unavailable, or any throw) → caller falls back to the box outline. The matrix sequence mirrors
     * vanilla `LivingEntityRenderer.render` exactly (`scale(-1,-1,1)` + `translate(0,-1.501,0)`, no
     * `/16` — entity-model vertices are pre-divided to block units at build); pose comes from the
     * state flags + `model.setAngles`. **Runtime-only-verifiable:** the skin-layer buffer flush at
     * `AFTER_ENTITIES` can only be confirmed in a live game — if a ghost renders as just the box
     * outline with this on, the skin/layer fetch is the path to recheck. Display-only: no detection.
     */
    @Volatile
    private var playerRenderer: AvatarRenderer<*>? = null

    /**
     * Per-ghost rolling walk-animation state. Vanilla drives a player's legs from a `LimbAnimator`
     * that the entity ticks; a replay ghost has no entity, so we derive the equivalent amplitude +
     * phase from the horizontal position delta between consecutive snaps. `lastX/lastZ` track the
     * previous snap (NaN sentinel = first frame for this uuid); `lastTickDist` is the per-tick
     * horizontal distance (held between ticks so the stride doesn't collapse when the snap is static
     * for the 3 frames between playhead ticks); `amp` lerps toward the clamped-speed target each
     * frame (smooth ramp up/down); `phase` advances every frame scaled by amplitude (a smooth, not
     * 20-tps-stepped, leg swing). Dist is capped so a seek/teleport never flings the stride. The
     * map survives across the replay session; cap-bounded in practice by player count.
     */
    private class WalkState {
        var lastX: Float = Float.NaN
        var lastZ: Float = Float.NaN
        var lastTick: Int = Int.MIN_VALUE
        var lastTickDist: Float = 0f
        var amp: Float = 0f
        var phase: Float = 0f
    }
    private val walkState = ConcurrentHashMap<java.util.UUID, WalkState>()

    /** Per-ghost rolling health for the [IustitiaConfig.clipHealthIndicator] damage popup + the
     *  smoothed displayed number: the last snap's RAW health (to detect a drop → popup), the smoothed
     *  [displayHealth] that lerps toward the raw target once per playhead tick (so the server's
     *  coarse/batched health sync ramps instead of snapping 3→18→7), + the last detected damage
     *  amount + the tick it happened (the `-dmg` popup shows for ~40 ticks). Survives across the
     *  replay session; bounded in practice by player count. Render-thread only (drawHumanoid is
     *  single-threaded). */
    private class HealthState {
        /** Last RAW target health (drop detection → -dmg popup). */
        var lastHealth: Float = Float.NaN
        /** Smoothed displayed health — lerps toward [lastHealth]'s target once per playhead tick.
         *  NaN until the first sighting (then snaps to the raw value). */
        var displayHealth: Float = Float.NaN
        /** Last playhead tick the smoothing was stepped on (gates the per-tick lerp, mirroring
         *  WalkState.lastTick at :630 — FPS-independent). */
        var displayTick: Int = Int.MIN_VALUE
        var dmgAmount: Float = 0f
        var dmgTick: Int = Int.MIN_VALUE
    }
    private val healthState = ConcurrentHashMap<java.util.UUID, HealthState>()

    // v8 ghost-equipment: registry-id-string → ItemStack cache. The same few items repeat across
    // ticks + players, so memoizing avoids re-resolving `BuiltInRegistries.ITEM.get` every ghost every
    // frame. Render-thread only (drawModel is single-threaded); bounded by the (small) set of
    // distinct item ids ever observed in a clip. ItemStack.EMPTY for blank / unknown / AIR ids.
    private val stackCache = HashMap<String, ItemStack>()
    private fun stackFor(id: String): ItemStack {
        if (id.isBlank()) return ItemStack.EMPTY
        stackCache[id]?.let { return it }
        val stack = try {
            val item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id))
            if (item === Items.AIR) ItemStack.EMPTY else ItemStack(item)
        } catch (_: Throwable) { ItemStack.EMPTY }
        stackCache[id] = stack
        return stack
    }

    // Per-tick alert-label cache (B3): the active-alert map only changes when the playhead frame
    // (frame.tick) changes — i.e. once per replay tick, not per render frame. Caching it avoids
    // rebuilding a filtered map every frame (and short-circuits to an empty map in the common
    // no-alerts case). Render-thread only (drawGhosts is single-threaded).
    private var cachedAlertTick: Int = Int.MIN_VALUE
    private var cachedAlertLabels: Map<java.util.UUID, String> = emptyMap()

    private fun drawModel(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        s: ReplayBuffer.PlayerSnap,
        tick: Int,
    ): Boolean {
        if (!try { ConfigManager.config.replayPlayerModels } catch (_: Throwable) { false }) return false
        // v8 ghost equipment toggle (held items + armor). Additive, default on. When off (or the live
        // entity-pass queue is unavailable) the ghost renders skin-only — today's behavior.
        val equipOn = try { ConfigManager.config.clipGhostEquipment } catch (_: Throwable) { false }
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        // resolve + cache the player renderer (stable per entity type; doesn't change with world)
        val renderer = playerRenderer ?: try {
            @Suppress("UNCHECKED_CAST")
            (mc.getEntityRenderDispatcher().getRenderer(player) as? AvatarRenderer<*>).also { playerRenderer = it }
        } catch (_: Throwable) { null }
        if (renderer == null) return false
        val model = try { renderer.model } catch (_: Throwable) { return false }
        // Real skin via the per-UUID cache (async-loaded by vanilla; Steve/Alex fallback). Fail-open → box.
        val skin = try { ReplaySkins.resolve(s.uuid()) } catch (_: Throwable) { return false }
        // Per-ghost walk state (rolling position-delta stride).
        val ws = walkState.computeIfAbsent(s.uuid()) { WalkState() }
        // A FRESH AvatarRenderState per ghost per frame, deliberately — NOT the per-type reuse
        // the entity ghosts below use, and the difference is not stylistic. This path submits the skin
        // through the deferred entity-pass queue at the bottom of this method, and
        // `SubmitNodeStorage.ModelCommand` is a record that holds the render state and is
        // drained after the whole ghost loop. One shared state per type would therefore be overwritten
        // by the next same-type ghost long before the first one's geometry was drawn — every player
        // ghost would render with the last player's pose. (`createRenderState()` is also the cheap
        // half: the profiler puts all of drawModel at ~0.4% of a frame.) Fail-open → box.
        val state: AvatarRenderState = try { renderer.createRenderState() } catch (_: Throwable) { return false }

        // --- populate the render state so the ghost poses, faces, walks and swings like the live
        //     player did. Field-by-field rationale (verified by disassembling vanilla 1.21.11):
        //       skin/baseScale/ageScale/visibility/light/invisible — basic posed-skin setup.
        //       hurt=false / deathTime=0 / shaking=false — explicitly suppress every damage channel.
        //         The overlay passed to Model.render is OverlayTexture.NO_OVERLAY (the transparent
        //         no-overlay row), NOT 0 — see the inline note at the render call below for why 0 was
        //         the constant-red bug. Pinning hurt/deathTime/shaking false is belt-and-suspenders.
        //       bodyYaw — setAngles does NOT read bodyYaw; the body-facing rotation lives in
        //         LivingEntityRenderer.setupTransforms (multiply POSITIVE_Y by 180-bodyYaw), which we
        //         bypass, so we apply that rotation on the matrix below. The field is set for any
        //         feature renderer that reads it.
        //       relativeHeadYaw=0 + pitch=s.pitch — the head nods to the look pitch (setAngles reads
        //         pitch); head doesn't turn independently (no separate head-yaw captured — body yaw
        //         IS the look yaw, so the whole ghost faces where the player looked).
        //       age — running time for the subtle idle arm/body sway (setAngles reads age).
        //       limbSwingAmplitude / limbSwingAnimationProgress — walk animation, derived below from
        //         the per-uuid position delta (limbAmplitudeInverse defaults to 1 in the state ctor,
        //         so the base setAngles leg math animates with no extra setup).
        //       handSwingProgress / leftArmPose / rightArmPose / mainArm / preferredArm — arm-swing
        //         (attack) animation; handSwingTicks captured per snap, arm poses EMPTY = natural hang. ---
        state.skin = skin
        state.scale = 1f
        state.ageScale = 1f
        state.showHat = true; state.showJacket = true
        state.showLeftPants = true; state.showRightPants = true
        state.showLeftSleeve = true; state.showRightSleeve = true
        state.showCape = true
        state.lightCoords = FULL_LIGHT
        state.isInvisible = false
        // v7 hurt red-flash: drive the vanilla hurt overlay from the recorded hurtTime (0 = not
        // recently hit). LivingEntityRenderer.getOverlay reads state.hurt → returns the red hurt-row
        // overlay UV when true, DEFAULT_UV when false (identical to the pre-v7 fixed DEFAULT_UV).
        state.hasRedOverlay = s.hurtTime > 0
        state.deathTime = 0f
        state.bodyRot = s.yaw
        state.yRot = s.yaw
        state.xRot = s.pitch
        state.ageInTicks = tick.toFloat()
        // Walk animation from the per-uuid rolling position delta (see WalkState). The advance is
        // gated on the PLAYHEAD FRAME advancing (tick - lastTick == 1), NOT on every render frame:
        // the playhead moves one frame per replay tick at 1× (slower at 0.5/0.25×), so the legs swing
        // at the replay's tick rate. The old code did `phase += amp * 0.45` every AFTER_ENTITIES
        // frame, pumping the legs at FPS-rate (~3× too fast at 60fps, worse uncapped) — the "too
        // fast / jittery" bug — and the same limbSwingAmplitude drives the arm pump, so sprint made
        // the hands flail. Tick-gated advance is FPS-independent and scales with replay speed.
        // Dead-zone: < 0.05 b/tick = standing still / server position noise → amp→0, legs freeze
        // (kills the idle fidget). Capped dist + a seek/gap reset stop teleport/seek from flinging.
        val tickJump = tick - ws.lastTick
        when {
            tickJump == 1 -> {
                if (!ws.lastX.isNaN()) {
                    val dx = s.x - ws.lastX
                    val dz = s.z - ws.lastZ
                    ws.lastTickDist = sqrt((dx * dx + dz * dz).toDouble()).toFloat().coerceAtMost(1.5f)
                }
                ws.lastX = s.x; ws.lastZ = s.z; ws.lastTick = tick
                val walkTarget = if (ws.lastTickDist > 0.05f) (ws.lastTickDist / 0.3f).coerceIn(0f, 1f) else 0f
                ws.amp += (walkTarget - ws.amp) * 0.3f
                // ~0.94/tick at full amp → ~0.5s stride cycle sprinting, ~1.5s strolling; cadence scales
                // with distance like vanilla. Capped dist above stops a teleport/gap fling. (0.94 = 2×
                // the original 0.47 — the tick-gated advance read as too slow, so the stride was doubled.)
                ws.phase += ws.amp * 0.94f
            }
            tickJump != 0 -> {
                // seek / gap / first frame: resync position + drop amp to 0 so the stride settles
                // instead of flinging. Phase keeps its value (resumes from mid-stride).
                ws.lastX = s.x; ws.lastZ = s.z; ws.lastTick = tick; ws.lastTickDist = 0f; ws.amp = 0f
            }
            // tickJump == 0: same playhead frame redrawn → hold the pose (no per-frame leg pump).
        }
        state.walkAnimationSpeed = ws.amp
        state.walkAnimationPos = ws.phase
        // HumanoidArm swing (attack): handSwingTicks polled from the live entity + captured per snap.
        // swingAnimation = ticks/6 ≈ vanilla getHandSwingProgress over the ~6-tick swing. HumanoidArm
        // poses EMPTY so the arms hang at the sides and swing when the player attacked.
        state.swingAnimation = (s.swingTicks / 6f).coerceIn(0f, 1f)
        state.leftArmPose = HumanoidModel.ArmPose.EMPTY
        state.rightArmPose = HumanoidModel.ArmPose.EMPTY
        state.mainArm = HumanoidArm.RIGHT
        when (s.pose) {
            ReplayBuffer.POSE_SNEAK -> state.isCrouching = true
            ReplayBuffer.POSE_GLIDE -> state.isFallFlying = true
            ReplayBuffer.POSE_SWIM -> state.isVisuallySwimming = true
            ReplayBuffer.POSE_RIPTIDE -> state.isAutoSpinAttack = true
            else -> {}
        }
        // pose the model limbs from the state (head pitch, body/limb walk, arm swing, sneak/glide).
        try { model.setupAnim(state) } catch (_: Throwable) { return false }

        // v8 ghost equipment: reconstruct the 6 recorded stacks (registry-id strings → ItemStack) and
        // populate the render state so the armor + held-item feature renderers draw them. Gated on the
        // config toggle + a live SubmitNodeCollector (features submit through the queue). Each
        // step fail-open — a bad id / a special-item NPE degrades to an empty slot, never breaks the
        // frame. `clearAndUpdate` populates the FINAL rightHandItemState/leftHandItemState in place
        // (they're `final ItemStackRenderState`, not reassignable) with a no-entity ItemOwner.
        if (equipOn) {
            try {
                state.headEquipment = stackFor(s.head)
                state.chestEquipment = stackFor(s.chest)
                state.legsEquipment = stackFor(s.legs)
                state.feetEquipment = stackFor(s.feet)
                state.rightHandItemStack = stackFor(s.mainHand)
                state.leftHandItemStack = stackFor(s.offHand)
                val imm = (renderer as? LivingEntityRendererAccessor)?.iustitia_getItemModelManager()
                val world = mc.level
                if (imm != null && world != null) {
                    // A no-entity ItemOwner: the ghost has no live LivingEntity, so the static
                    // ArmedEntityRenderState.updateRenderState path (which requires one) is bypassed.
                    // Most item models don't touch getEntity(); a few special ones (trident/spyglass/
                    // custom) might NPE → contained by the per-feature try/catch at the render call.
                    val itemCtx = GhostHeldItemContext(world, Vec3(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()), s.yaw)
                    try {
                        imm.updateForTopItem(state.rightHandItemState, state.rightHandItemStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, world, itemCtx, FULL_LIGHT)
                    } catch (_: Throwable) {}
                    try {
                        imm.updateForTopItem(state.leftHandItemState, state.leftHandItemStack, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, world, itemCtx, FULL_LIGHT)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        val texture = try { skin.body().texturePath() } catch (_: Throwable) { return false }
        val layer = try { model.renderType(texture) ?: return false } catch (_: Throwable) { return false }

        matrices.pushPose()
        try {
            matrices.translate(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
            // Body-facing rotation — replicates vanilla LivingEntityRenderer.setupTransforms for a
            // non-sleeping entity: multiply POSITIVE_Y by (180 - bodyYaw). setupTransforms runs
            // BEFORE the -1,-1,1 flip in vanilla's render sequence, so we insert it here before the
            // flip. Without this the body never rotates to the player's yaw (setAngles doesn't read
            // bodyYaw) → the ghost always faced south regardless of where the player looked (the
            // "camera not logged" bug). Runtime-verifiable sign; fail-open per ghost.
            matrices.mulPose(Matrix4f().rotation(Axis.YP.rotationDegrees(180f - s.yaw)))
            // Replicate vanilla LivingEntityRenderer.render EXACTLY from here: entity-model vertices
            // are ALREADY in block units (ModelPart$Vertex divides pixel coords by 16 at build time),
            // so there is NO /16 scale; scale(-1,-1,1) flips the model out of its +Y-down definition
            // space; translate(0,-1.501,0) seats the feet at the snap origin (vanilla's constants).
            matrices.scale(-1f, -1f, 1f)
            matrices.translate(0f, -1.501f, 0f)
            // OverlayTexture: the hurt red-flash. Vanilla's LivingEntityRenderer.getOverlayCoords(state,
            // partial) = packUv(getU(partial), getV(state.hurt)) — getV(true) selects the translucent
            // RED hurt row (rows 0-7), getV(false) returns the transparent DEFAULT row. We set
            // state.hurt = (hurtTime > 0) above, so a recorded hit tints that ghost red for ~10 ticks
            // and a non-hit ghost gets DEFAULT_UV (identical to the pre-v7 fixed DEFAULT_UV — no
            // behaviour change for unhurt ghosts). Passing 0 as the partial just keeps U=0 (the white
            // hurt sparkle isn't used for the red tint, which lives in V); partialTick=0 is fine since
            // the playhead advances per-tick. Fail-open to DEFAULT_UV.
            val overlay = if (s.hurtTime > 0)
                try { LivingEntityRenderer.getOverlayCoords(state, 0f) } catch (_: Throwable) { OverlayTexture.NO_OVERLAY }
            else OverlayTexture.NO_OVERLAY
            // Submit the ghost skin into the frame's collector (proper z-order with the captured
            // world + the feature-submitted armor). The trailing no-op defaults (color=-1, sprite=null,
            // outlineColor=0) are vanilla's own values for a non-spectator / non-mix entity.
            try {
                collector.submitModel(model, state, matrices, layer, FULL_LIGHT, overlay, -1, null, 0)
            } catch (_: Throwable) {}
            // v8 equipment features: armor + held items. Filter to the two equipment features the
            // user asked for (PlayerHeldItemFeatureRenderer extends ItemInHandLayer, so the
            // base `is`-check covers it). Per-feature try/catch contains any NPE from a special
            // item model (trident/spyglass/custom) or an unset state field — one bad feature skips,
            // the rest + the skin survive.
            if (equipOn) {
                val feats = (renderer as? LivingEntityRendererAccessor)?.iustitia_getFeatures()
                if (feats != null) {
                    for (f in feats) {
                        if (f !is HumanoidArmorLayer<*, *, *> && f !is ItemInHandLayer<*, *>) continue
                        try {
                            @Suppress("UNCHECKED_CAST")
                            (f as RenderLayer<AvatarRenderState, *>).submit(
                                matrices, collector, FULL_LIGHT, state, state.yRot, state.xRot)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {
            return false
        } finally {
            matrices.popPose()
        }
        return true
    }

    /** Submit an axis-aligned box outline in the CURRENT matrix frame (offset 0; caller positions). */
    private fun box(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        minx: Double, miny: Double, minz: Double,
        maxx: Double, maxy: Double, maxz: Double,
        color: Int, width: Float,
    ) {
        val shape = Shapes.box(minx, miny, minz, maxx, maxy, maxz)
        collector.submitShapeOutline(matrices, shape, RenderTypes.LINES, color, width, false)
    }

    // ---- v13 non-player entity ghosts (SnapClip port) ------------------------------------------------
    //
    // A captured mob/animal is drawn through the vanilla entity renderer resolved from its captured
    // registry type (`BuiltInRegistries.ENTITY_TYPE`), driven by a render state built from its prototype
    // entity (which is what gives the model its correct dimensions/parts) plus the snap's pose/yaw/
    // health. Boats/minecarts have no model of their own — their renderer submits into an
    // SubmitNodeCollector instead — so they go through [ImmediateRenderQueue], which emits that
    // work straight into the overlay's [MultiBufferSource]. Both paths are display-only and
    // fail-open per entity; the renderer/prototype caches are keyed by type id so a scene with 20
    // zombies resolves the renderer once. Runtime-only-verifiable (the model transforms + the
    // non-living submit path can only be confirmed in a live client).

    /** typeId → vanilla living renderer (null when the type has no living renderer / resolution failed). */
    private val entityRendererCache = ConcurrentHashMap<String, LivingEntityRenderer<*, *, *>?>()
    /** typeId → a fabricated prototype living entity (used to seed `updateRenderState`). */
    private val entityPrototypeCache = ConcurrentHashMap<String, net.minecraft.world.entity.LivingEntity?>()
    /** typeId → vanilla non-living renderer (boats/minecarts). */
    private val nonLivingRendererCache = ConcurrentHashMap<String, EntityRenderer<*, *>?>()
    /** typeId → a fabricated prototype non-living entity. */
    private val nonLivingPrototypeCache = ConcurrentHashMap<String, Entity?>()

    /**
     * typeId to the ONE render state that type's ghost is drawn with, every frame.
     *
     * Both entity-ghost paths used to call `createRenderState()` per ghost per frame, building a fresh
     * object solely to overwrite all of it and hand it to a consumer that reads it synchronously.
     * Reuse is sound here because the state is keyed per TYPE (so the concrete state class always
     * matches the renderer that fills it), every field the draw reads is re-derived each frame by
     * `updateRenderState` from the cached prototype plus the explicit overwrites below, and nothing
     * retains the reference past the call -- [ImmediateRenderQueue] writes its vertices immediately,
     * and the living path renders straight into a vertex buffer.
     *
     * The player path must NOT do this, and its own comment no longer claims otherwise: its state is
     * captured by the deferred `queue.submitModel` command and drained after the ghost loop. Cleared in
     * [clearGhostCaches] so a replay in another world cannot reuse a state seeded from the previous
     * world's prototype.
     */
    private val entityStateCache = ConcurrentHashMap<String, LivingEntityRenderState>()
    /** typeId to the reused render state for a non-living ghost (boats/minecarts); see [entityStateCache]. */
    private val nonLivingStateCache = ConcurrentHashMap<String, EntityRenderState>()

    /** Resolve (and cache) the vanilla living renderer for a captured entity type id, or null. */
    private fun entityRendererFor(typeId: String): LivingEntityRenderer<*, *, *>? {
        entityRendererCache[typeId]?.let { return it }
        val r = lookupEntityRenderer(typeId)
        entityRendererCache[typeId] = r
        return r
    }

    private fun lookupEntityRenderer(typeId: String): LivingEntityRenderer<*, *, *>? = try {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return null
        val id = Identifier.parse(typeId)
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return null
        val type = BuiltInRegistries.ENTITY_TYPE.getValue(id)
        val proto = type.create(world, EntitySpawnReason.COMMAND) ?: return null
        if (proto !is net.minecraft.world.entity.LivingEntity) return null
        entityPrototypeCache[typeId] = proto
        val renderer = mc.getEntityRenderDispatcher().getRenderer(proto)
        renderer as? LivingEntityRenderer<*, *, *>
    } catch (_: Throwable) { null }

    /** Resolve (and cache) the vanilla non-living renderer for a captured entity type id, or null. */
    private fun nonLivingRendererFor(typeId: String): EntityRenderer<*, *>? {
        nonLivingRendererCache[typeId]?.let { return it }
        val r = lookupNonLivingRenderer(typeId)
        nonLivingRendererCache[typeId] = r
        return r
    }

    private fun lookupNonLivingRenderer(typeId: String): EntityRenderer<*, *>? = try {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return null
        val id = Identifier.parse(typeId)
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return null
        val type = BuiltInRegistries.ENTITY_TYPE.getValue(id)
        val proto = type.create(world, EntitySpawnReason.COMMAND) ?: return null
        if (proto is net.minecraft.world.entity.LivingEntity) return null
        nonLivingPrototypeCache[typeId] = proto
        mc.getEntityRenderDispatcher().getRenderer(proto)
    } catch (_: Throwable) { null }

    /**
     * Draw one living entity ghost (mob/animal) at its recorded pose: resolve the renderer for its
     * type, build a render state from the prototype, overwrite the recorded pose/yaw/health, derive a
     * limb-swing from consecutive-snap movement, then render the bare model with the vanilla
     * `scale(-1,-1,1)` + `translate(0,-1.501,0)` body transform. Culled beyond [ENTITY_RENDER_RADIUS].
     * Fail-open (returns quietly on any missing piece).
     */
    private fun drawEntityGhost(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        e: ReplayBuffer.EntitySnap,
        camPos: Vec3,
        offset: Vec3,
        tick: Int,
    ) {
        val ex = e.x.toDouble() + offset.x
        val ey = e.y.toDouble() + offset.y
        val ez = e.z.toDouble() + offset.z
        val dx = ex - camPos.x
        val dy = ey - camPos.y
        val dz = ez - camPos.z
        if (dx * dx + dy * dy + dz * dz > ENTITY_RENDER_RADIUS_SQ) return

        val cached = entityRendererFor(e.typeId) ?: return
        @Suppress("UNCHECKED_CAST")
        val r = cached as LivingEntityRenderer<net.minecraft.world.entity.LivingEntity, LivingEntityRenderState, EntityModel<LivingEntityRenderState>>
        // ONE state per entity type, reused every frame (see [entityStateCache] for why that is safe
        // here and not on the player path). Fail-open → skip this ghost.
        val state: LivingEntityRenderState = try {
            entityStateCache.getOrPut(e.typeId) { r.createRenderState() }
        } catch (_: Throwable) { return }
        val model: EntityModel<LivingEntityRenderState> = try { r.model } catch (_: Throwable) { return }

        val proto = entityPrototypeCache[e.typeId]
        if (proto != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                (r as LivingEntityRenderer<net.minecraft.world.entity.LivingEntity, LivingEntityRenderState, *>)
                    .extractRenderState(proto, state, 0f)
            } catch (_: Throwable) {
            }
        }

        state.scale = 1f
        state.ageScale = 1f
        state.lightCoords = FULL_LIGHT
        state.isInvisible = false
        state.hasRedOverlay = e.hurtTime > 0
        state.deathTime = 0f
        state.isBaby = false
        state.bodyRot = e.bodyYaw
        state.yRot = e.headYaw
        state.xRot = e.pitch
        state.ageInTicks = tick.toFloat()
        state.isAutoSpinAttack = (e.pose == ReplayBuffer.POSE_RIPTIDE)

        // Movement-derived limb swing (no entity to tick a LimbAnimator): distance between consecutive
        // snaps → amplitude, integrated into phase. Reuses the players' [WalkState] keyed by uuid (a mob
        // and a player never share a uuid, so one map is fine). Seek/scrub (tickJump != 1) resets to 0.
        val ws = walkState.computeIfAbsent(e.uuid()) { WalkState() }
        val tickJump = tick - ws.lastTick
        when {
            tickJump == 1 -> {
                if (!ws.lastX.isNaN()) {
                    val ddx = e.x - ws.lastX
                    val ddz = e.z - ws.lastZ
                    ws.lastTickDist = sqrt((ddx * ddx + ddz * ddz).toDouble()).toFloat().coerceAtMost(1.5f)
                }
                ws.lastX = e.x; ws.lastZ = e.z; ws.lastTick = tick
                val walkTarget = if (ws.lastTickDist > 0.05f) (ws.lastTickDist / 0.3f).coerceIn(0f, 1f) else 0f
                ws.amp += (walkTarget - ws.amp) * 0.3f
                ws.phase += ws.amp * 0.94f
            }
            tickJump != 0 -> {
                ws.lastX = e.x; ws.lastZ = e.z; ws.lastTick = tick; ws.lastTickDist = 0f; ws.amp = 0f
            }
        }
        state.walkAnimationSpeed = ws.amp
        state.walkAnimationPos = ws.phase

        try { model.setupAnim(state) } catch (_: Throwable) { return }
        val texture = try { r.getTextureLocation(state) } catch (_: Throwable) { return }
        val layer = try { model.renderType(texture) ?: return } catch (_: Throwable) { return }

        matrices.pushPose()
        try {
            matrices.translate(e.x.toDouble(), e.y.toDouble(), e.z.toDouble())
            matrices.mulPose(Matrix4f().rotation(Axis.YP.rotationDegrees(180f - e.bodyYaw)))
            matrices.scale(-1f, -1f, 1f)
            matrices.translate(0f, -1.501f, 0f)
            val overlay = if (e.hurtTime > 0)
                try { LivingEntityRenderer.getOverlayCoords(state, 0f) } catch (_: Throwable) { OverlayTexture.NO_OVERLAY }
            else OverlayTexture.NO_OVERLAY
            try {
                collector.submitModel(model, state, matrices, layer, FULL_LIGHT, overlay, -1, null, 0)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        } finally {
            matrices.popPose()
        }
    }

    /**
     * Draw one non-living entity ghost (boat/minecart) at its recorded pose via the vanilla entity
     * renderer + [ImmediateRenderQueue] (which turns the renderer's deferred submissions into direct
     * vertex writes). Culled beyond [ENTITY_RENDER_RADIUS]. Fail-open.
     */
    private fun drawNonLivingEntityGhost(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        cameraState: CameraRenderState,
        e: ReplayBuffer.EntitySnap,
        camPos: Vec3,
        offset: Vec3,
        tick: Int,
    ) {
        val ex = e.x.toDouble() + offset.x
        val ey = e.y.toDouble() + offset.y
        val ez = e.z.toDouble() + offset.z
        val dx = ex - camPos.x
        val dy = ey - camPos.y
        val dz = ez - camPos.z
        if (dx * dx + dy * dy + dz * dz > ENTITY_RENDER_RADIUS_SQ) return

        val cached = nonLivingRendererFor(e.typeId) ?: return
        @Suppress("UNCHECKED_CAST")
        val r = cached as EntityRenderer<Entity, EntityRenderState>
        // ONE state per entity type, reused every frame — same rationale as [entityStateCache], and
        // even safer here: [ImmediateRenderQueue] drains each submission on the spot, so this state is
        // never held past the call below.
        val state: EntityRenderState = try {
            nonLivingStateCache.getOrPut(e.typeId) { r.createRenderState() }
        } catch (_: Throwable) { return }

        val proto = nonLivingPrototypeCache[e.typeId]
        if (proto != null) {
            try { r.extractRenderState(proto, state, 0f) } catch (_: Throwable) {}
        }

        state.x = e.x.toDouble()
        state.y = e.y.toDouble()
        state.z = e.z.toDouble()
        state.lightCoords = FULL_LIGHT
        state.ageInTicks = tick.toFloat()
        state.isInvisible = false

        when (state) {
            is BoatRenderState -> state.yRot = e.bodyYaw
            is MinecartRenderState -> {
                state.yRot = e.bodyYaw
                // 26.3: the minecart display block lives in displayBlockModel (starts empty)
            }
        }

        matrices.pushPose()
        try {
            matrices.translate(e.x.toDouble(), e.y.toDouble(), e.z.toDouble())
            r.submit(state, matrices, collector, cameraState)
        } catch (_: Throwable) {
        } finally {
            matrices.popPose()
        }
    }


    /** Drop every replay-scoped ghost cache (called from [ReplayState.stop]). Idempotent.
     *  The renderer/prototype/state caches are the important ones: their entries are fabricated
     *  against the `mc.level`/dispatcher live at LOOKUP time, so a clip played in a
     *  different world/dimension after this replay would keep rendering ghosts with
     *  renderers + prototype entities bound to the previous world (the old version cleared
     *  only the walk/health per-uuid state and leaked all of these for the JVM's lifetime).
     *  Render-thread only (stop is client-thread; the maps are either thread-safe or only
     *  touched on this thread, and they are empty again by the next replay's first frame). */
    fun clearGhostCaches() {
        try {
            walkState.clear()
            healthState.clear()
            entityRendererCache.clear()
            entityPrototypeCache.clear()
            nonLivingRendererCache.clear()
            nonLivingPrototypeCache.clear()
            entityStateCache.clear()
            nonLivingStateCache.clear()
            stackCache.clear()
            playerRenderer = null
            cachedAlertLabels = emptyMap()
            cachedAlertTick = Int.MIN_VALUE
        } catch (_: Throwable) {}
    }

    private fun tierColor(tier: FlagHistory.Tier): Int = when (tier) {
        FlagHistory.Tier.RED -> RED_COLOR
        FlagHistory.Tier.YELLOW -> YELLOW_COLOR
        FlagHistory.Tier.GREEN -> GREEN_COLOR
    }

    /** Bottom-center replay banner: focus name + speed + a progress bar. */
    private fun renderHud(context: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null) return
        val tr = mc.font ?: return
        val speed = ReplayState.currentSpeed()
        val paused = ReplayState.isPaused()
        val focus = ReplayState.focusName() ?: "scene"
        val pauseTxt = if (paused) "§e⏸ " else ""
        val line = Component.literal("§8[§diustitia§8] §6▶ §fReplay §e$focus §7$pauseTxt§7${NumFmt.d(digits = 2, v = speed)}×")
        val padX = 6
        val sw = mc.getWindow().guiScaledWidth
        val sh = mc.getWindow().guiScaledHeight
        val lineW = tr.width(line)
        val barW = 160
        val panelW = maxOf(lineW, barW) + padX * 2
        val x = (sw - panelW) / 2
        val y = sh - 30
        context.fill(x, y, x + panelW, y + 22, BG)
        context.text(tr, line, x + padX, y + 3, WHITE)
        // Progress bar.
        val bx = x + padX
        val by = y + 15
        context.fill(bx, by, bx + barW, by + 4, BAR_BG)
        val prog = ReplayState.progress()
        if (prog > 0f) context.fill(bx, by, bx + (barW * prog).toInt().coerceIn(0, barW), by + 4, BAR_FG)
        // Alert markers on the bar: one tick per captured alert, colored by the offender's tier,
        // positioned where the alert sits in the window. Capped so a busy replay doesn't paint a
        // solid bar. Fail-open: a bad marker never breaks the HUD.
        try {
            val range = ReplayState.windowTickRange()
            val alerts = ReplayState.alerts()
            if (range != null && alerts.isNotEmpty()) {
                val span = (range.last - range.first).coerceAtLeast(1)
                val cap = 40
                var drawn = 0
                for (a in alerts) {
                    if (drawn >= cap) break
                    val frac = ((a.tick - range.first).toFloat() / span).coerceIn(0f, 1f)
                    val mx = bx + (barW * frac).toInt().coerceIn(0, barW)
                    val tier = try { FlagHistory.tierFor(a.uuid()) } catch (_: Throwable) { FlagHistory.Tier.GREEN }
                    val col = when (tier) { FlagHistory.Tier.RED -> RED_COLOR; FlagHistory.Tier.YELLOW -> YELLOW_COLOR; FlagHistory.Tier.GREEN -> GREEN_COLOR }
                    context.fill(mx, by - 2, mx + 1, by + 6, col)
                    drawn++
                }
            }
        } catch (_: Throwable) {}
    }

    private const val WHITE = -1
    private val BG = 0x90000000.toInt()
    private val BAR_BG = 0x50000000.toInt()
    private val BAR_FG = 0xC0FFE070.toInt()
}