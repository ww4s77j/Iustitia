package dev.iustitia.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import dev.iustitia.config.ConfigManager
import dev.iustitia.replay.ReplayState
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.SubmitNodeCollector
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.joml.Vector4fc
import java.util.Optional
import java.util.OptionalDouble

/**
 * Draws the active clip's captured chunk world as **solid, textured blocks** (the real map) each
 * frame, under the same shared origin translate [dev.iustitia.render.ReplayRenderer] applies, so the
 * chunk world + ghosts move as one rigid block to the user. The block list is built **lazily per
 * chunk** by [ChunkMesher] as **per-block, face-culled, precomputed quads** (only the quads vanilla
 * would draw — buried faces are dropped at bake time, and `getQuads(null)` general quads are kept so
 * non-cube blocks don't get holes).
 *
 * ## C2 — static GPU mesh (the v1.3.0 perf rework)
 *
 * Each chunk's quads are baked ONCE into a static GPU vertex buffer (`GpuBuffer`) at bake time
 * ([ChunkMesher.bakeStatic]) — in the layer's vertex format (`POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL`,
 * `PrimitiveTopology.QUADS`), with **absolute recorded coords** already baked into the vertices. Each frame
 * the renderer draws every in-range baked chunk via **one `RenderPass` per RenderType** (at most two
 * layers: `entityCutout` + `blockTranslucentCull`), binding one model-view matrix (`matrices.last()`,
 * the camera rotation × the shared origin translate) as the `"DynamicTransforms"` uniform and the
 * shared sequential quad index buffer. No per-frame per-vertex emission, no per-frame re-upload —
 * the ~90% `class_287.method_22919` emission + ~28% `nglNamedBufferSubData` upload that dominated the
 * `/ius debugfps` profile (95% of the iustitia frame) are gone; the per-frame cost is a handful of
 * `setVertexBuffer` + `drawIndexed` calls.
 *
 * **Matrix semantics:** `RenderSystem.bindDefaultUniforms(pass)` binds `Projection`/`Fog`/`Globals`/
 * `Lighting` but NOT a model-view; the pipeline shader's sole model-view is `"DynamicTransforms"` (the
 * entity/Immediate path sets it to RenderSystem's model-view matrix, which is identity at the frame's
 * collect point while `ctx.matrices()` carries the full camera+origin transform). So we bake raw
 * recorded coords and bind `DynamicTransforms = getModelView × matrices.last().pose()` →
 * `Projection × matrices.last() × rawVert`, identical to the old `Projection × (matrices.last() × rawVert)`
 * per-vertex bake.
 *
 * **Fail-open:** the whole static-draw path is wrapped in a try/catch; any throw (no device,
 * render-target/texture not ready, a GL error) falls back to [emitQuads] — the old per-block quad
 * emit, now a submit-node custom-geometry submission (`SubmitNodeCollector.submitCustomGeometry`,
 * the 26.3 replacement for the removed `MultiBufferSource` bridge) — for every collected chunk that
 * frame. So a C2 error never crashes the client and the world keeps rendering (just without the FPS
 * win). The `byLayer` `BlockQuads` the fallback needs are kept on [ChunkMesher.ChunkBlocks] for
 * exactly this.
 *
 * **RenderTarget target (runtime-verify):** the `RenderPass` targets
 * `Minecraft.gameRenderer.mainRenderTarget()` — the standard render target at `COLLECT_SUBMITS`
 * time (was `getFramebuffer()` at `AFTER_ENTITIES`). This is the one piece the build can't verify;
 * if live testing shows blocks missing / wrong depth (wrong target), the fail-open keeps them
 * visible via [emitQuads] while the target is iterated. See the C2 plan.
 *
 * ## Per-chunk render-distance cull + lazy progressive baking (unchanged)
 *
 * A max-radius capture ([IustitiaConfig.clipChunkRadius], up to 17×17 = 289 chunks) can hold hundreds
 * of thousands of surface blocks. Two things keep a playclip playable:
 *
 * 1. **Render-distance cull** — each frame we only consider chunks within
 *    [IustitiaConfig.clipChunkRenderDistance] (default 6, clamped 4..12) of the camera in **recorded
 *    space** (the camera's recorded-space position = `camPos - relocOffset`). Chunks outside that
 *    chebyshev radius are neither rendered NOR baked.
 * 2. **Lazy progressive baking** — of the in-range chunks, only the ones already baked render; the
 *    nearest UNbaked in-range chunks are baked on demand up to a small **per-frame block budget**
 *    ([LAZY_BAKE_BLOCK_BUDGET], counted in surface blocks). So the clip's world streams in from the
 *    camera outward over the first second instead of appearing in one synchronous bake spike. Far
 *    chunks only bake if you fly toward them; chunks you never visit are never baked.
 *
 * ## Gating
 *
 * No-op unless `ReplayState.active && ReplayState.chunks != null` — so a wireframe-terrain clip
 * (`chunks == null`) and a plain `/ius replay` are unaffected; the live world is only hidden by
 * [dev.iustitia.mixin.SectionRenderStateMixin] when `chunks != null` too. [free] drops the mesher cache
 * (closing the static `GpuBuffer`s).
 */
object ChunkWorldRenderer {

    /** Fullbright light coord (LightTexture.MAX_LIGHT_COORDINATE = 0xF000F0) — replays don't
     *  need real AO/skylight; flat-lit solid blocks read clearly. Baked into the static buffer at bake
     *  time, and used by the [emitQuads] fallback. */
    private const val FULL_LIGHT = 0xF000F0

    /**
     * Per-frame lazy-bake budget in **surface-block count**. Each frame we bake the nearest unbaked
     * in-range chunks until the cumulative surface-block count of newly-baked chunks reaches this, so
     * the bake work spreads evenly across frames. ~3000 blocks/frame ≈ a handful of arena chunks,
     * baking the whole in-range ring (≤169 chunks at radius 6) over ~1 s at 60 fps with no
     * single-frame spike. At least one chunk always bakes per frame (if any unbaked in-range) so the
     * view never stalls entirely.
     */
    private const val LAZY_BAKE_BLOCK_BUDGET = 3000

    /** Name supplier for the static-draw `RenderPass` (mirrors vanilla's pass-naming convention). */
    private val passName: java.util.function.Supplier<String> =
        java.util.function.Supplier<String> { "iustitia-chunkworld" }

    /** Reused per-frame collections — render thread only ([render]/[drawStaticAll] run there and both
     *  are cleared at frame start, so leftovers from a failed frame can't leak into the next one). */
    private val collectedChunks = ArrayList<ChunkMesher.ChunkBlocks>()
    private val staticByLayer = HashMap<RenderType, ArrayList<ChunkMesher.StaticChunkLayer>>()

    // Reused joml args for the per-frame DynamicTransforms uniform write (DynamicGpuData.writeTransform
    // copies the values synchronously, so reuse is safe — no per-frame allocation).
    private val COLOR_MOD = Vector4f(1f, 1f, 1f, 1f)
    private val MODEL_OFFSET = Vector3f()
    private val TEX_IDENTITY = Matrix4f()
    /** Scratch for the per-frame model-view (= getModelView × the stack's position matrix).
     *  `Matrix4f.mul(right, dest)` overwrites `dest` outright rather than accumulating, and nothing
     *  holds the reference past the `writeTransform` that copies it, so one instance serves every frame. */
    private val MODEL_VIEW = Matrix4f()

    /**
     * Draw the clip's chunk world. [matrices] is already translated by the shared origin translate
     * (`-camPos + relocOffset`) in [dev.iustitia.render.ReplayRenderer.drawGhosts]; the static-draw
     * path binds `matrices.last().pose()` (that same transform) as the model-view uniform,
     * and the [emitQuads] fallback translates each block to its absolute recorded coord on top of it.
     * Both land at `recorded - camPos + offset` — same frame as the ghosts, so the world + ghosts move
     * together. [camPos] is the live camera world pos; [relocOffset] the clip relocation.
     *
     * Each frame: snapshot-key the mesher, compute the recorded-space camera chunk, then walk the
     * in-range chunk coords nearest-first (chebyshev rings outward from the camera chunk), applying
     * the behind-camera cull + lazy bake and **collecting** the in-range baked chunks. After the walk,
     * draw them all via [drawStaticAll] (batched, one `RenderPass` per layer); on any throw fall back
     * to [emitQuads] for each collected chunk. Fail-open per frame + per chunk.
     */
    fun render(matrices: PoseStack, collector: SubmitNodeCollector, camPos: Vec3, relocOffset: Vec3) {
        if (!ReplayState.active) return
        val snap = ReplayState.chunks ?: return
        try {
            ChunkMesher.ensureSnapshot(snap)
            // Recorded-space camera = camPos - relocOffset. Cull chunks whose chebyshev distance from
            // this point exceeds the configured render distance.
            val rdx = camPos.x - relocOffset.x
            val rdy = camPos.y - relocOffset.y
            val rdz = camPos.z - relocOffset.z
            val camChunkX = Math.floor(rdx / 16.0).toInt()
            val camChunkZ = Math.floor(rdz / 16.0).toInt()
            val dist = ConfigManager.config.clipChunkRenderDistance.coerceIn(4, 12)
            // Camera look vector for the behind-camera cull (see [behindCamera]). From the CAMERA's
            // pitch/yaw via Entity.calculateViewVector (the static 26.3 helper — `getRotationVector`
            // is Vec2-only now; verified +Z at yaw 0 / pitch 0). Null when the camera/player can't be
            // read → skip the cull this frame (fail-open: render every in-range chunk, the pre-cull
            // behaviour).
            val mc = Minecraft.getInstance()
            val fwd: Vec3? = try {
                val cam = mc.gameRenderer.mainCamera()
                val p = mc.player
                if (p != null) Entity.calculateViewVector(cam.xRot(), cam.yRot()) else null
            } catch (_: Throwable) { null }

            // Collect the in-range, not-behind-camera, baked chunks (the behind-camera cull + lazy bake
            // happen in [processChunk]; it returns the chunk to draw via [drawStaticAll] / [emitQuads]).
            // [collectedChunks] is a reused field — render-thread-only, cleared at frame start.
            collectedChunks.clear()
            var budget = LAZY_BAKE_BLOCK_BUDGET
            for (d in 0..dist) {
                if (d == 0) {
                    budget -= processChunk(camChunkX, camChunkZ, 0, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                    continue
                }
                for (cx in (camChunkX - d)..(camChunkX + d)) {
                    budget -= processChunk(cx, camChunkZ - d, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                    budget -= processChunk(cx, camChunkZ + d, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                }
                for (cz in (camChunkZ - d + 1)..(camChunkZ + d - 1)) {
                    budget -= processChunk(camChunkX - d, cz, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                    budget -= processChunk(camChunkX + d, cz, d, snap, collectedChunks, budget, rdx, rdy, rdz, fwd)
                }
            }
            if (collectedChunks.isEmpty()) return
            // C2: try the batched static-GPU-mesh draw; on any throw fall back to the per-block
            // putBakedQuad emit for every collected chunk (the fail-open path — correct rendering,
            // no FPS win).
            try {
                drawStaticAll(collectedChunks, matrices)
            } catch (_: Throwable) {
                for (cb in collectedChunks) {
                    try { emitQuads(cb, matrices, collector) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            // fail-open: chunk world drops this frame, ghosts + live render continue
        }
    }

    /**
     * Collect the chunk at (cx,cz) into [collected] if it's already baked (and not behind the camera);
     * otherwise bake it on demand (if the per-frame [budget] remains, or it's the camera's own chunk
     * — d == 0 — which always bakes so the view directly around the camera never stalls) and collect
     * it. The behind-camera cull skips the per-frame draw cost for a chunk whose ENTIRE baked-block
     * AABB is behind the camera plane (the chunk still bakes so it's ready the instant the user turns
     * around). Skips chunks the snapshot has no data for, and unbaked chunks with no budget left (they
     * stream in on a later frame).
     *
     * @return the surface-block count of the chunk it just baked (so the caller can decrement its
     *   running budget), or 0 when the chunk was already baked / had no data / was skipped for budget.
     *   Fail-open per chunk (returns 0 on a throw).
     */
    private fun processChunk(
        cx: Int, cz: Int, d: Int,
        snap: dev.iustitia.replay.ChunkSnapshot,
        collected: ArrayList<ChunkMesher.ChunkBlocks>,
        budget: Int,
        rdx: Double, rdy: Double, rdz: Double, fwd: Vec3?,
    ): Int = try {
        if (ChunkMesher.chunkRecAt(cx, cz) == null) return 0
        val cb = ChunkMesher.bakedChunk(cx, cz)
        if (cb != null) {
            if (cb.blockCount != 0 && fwd != null && behindCamera(cb, rdx, rdy, rdz, fwd)) return 0
            collected.add(cb)
            return 0
        }
        if (budget > 0 || d == 0) {
            val newly = ChunkMesher.bakeChunk(snap, cx, cz)
            if (newly != null) {
                if (newly.blockCount != 0 && fwd != null && behindCamera(newly, rdx, rdy, rdz, fwd)) return newly.blockCount
                collected.add(newly)
                return newly.blockCount
            }
        }
        // budget spent + not the camera chunk → skip this frame; it bakes on a later frame.
        0
    } catch (_: Throwable) {
        0
    }

    /**
     * Pop-free behind-camera cull for one baked chunk. The chunk's baked-block AABB (recorded coords,
     * true extent) is tested against the camera plane (through the recorded-space camera `(rdx,rdy,rdz)`,
     * normal [fwd] — the camera's look vector, rotation-only so identical in recorded space). Returns
     * true only when EVERY one of the 8 AABB corners is behind the camera plane (max dot < 0) — i.e.
     * the whole chunk is off-screen — so a chunk with any corner in front is NEVER culled (can't pop).
     * The max-dot is computed per-axis by picking the corner that maximizes each axis's contribution
     * given the sign of [fwd], avoiding an explicit 8-corner loop. [fwd] is non-null + normalized-ish
     * (Entity.calculateViewVector returns a unit vector); the test is scale-invariant in sign anyway.
     */
    private fun behindCamera(
        cb: ChunkMesher.ChunkBlocks,
        rdx: Double, rdy: Double, rdz: Double, fwd: Vec3,
    ): Boolean {
        val dx0 = cb.minX - rdx; val dx1 = cb.maxX - rdx
        val dy0 = cb.minY - rdy; val dy1 = cb.maxY - rdy
        val dz0 = cb.minZ - rdz; val dz1 = cb.maxZ - rdz
        val mx = if (fwd.x > 0) dx1 * fwd.x else dx0 * fwd.x
        val my = if (fwd.y > 0) dy1 * fwd.y else dy0 * fwd.y
        val mz = if (fwd.z > 0) dz1 * fwd.z else dz0 * fwd.z
        return (mx + my + mz) < 0.0
    }

    /**
     * C2 static-draw: draw every collected chunk via **one `RenderPass` per present RenderType**.
     * Each chunk's quads are already in a static `GpuBuffer` (baked once by [ChunkMesher.bakeStatic])
     * with absolute recorded coords, so per chunk we just bind its vertex buffer + issue one
     * `drawIndexed` against the shared sequential quad index buffer. The model-view is
     * `matrices.last().pose()` (the camera rotation × the shared origin translate) bound
     * once per layer as `"DynamicTransforms"`; the layer's own texture bindings (block atlas
     * `Sampler0`, overlay/lightmap) come from `RenderType.prepare()` — the 26.3 replacement for the
     * old manual `TextureManager.getTexture(BLOCK_ATLAS_TEXTURE)` + lightmap/sampler resolution.
     * Throws on any missing handle (device/render target/texture not ready) so the caller falls back
     * to [emitQuads]. All targets/accessors verified against the 26.3 jar (see the C2 plan).
     */
    private fun drawStaticAll(chunks: List<ChunkMesher.ChunkBlocks>, matrices: PoseStack) {
        if (chunks.isEmpty()) return
        val mc = Minecraft.getInstance()
        val dev = RenderSystem.getDevice() ?: throw IllegalStateException("no device")
        val fb = mc.gameRenderer.mainRenderTarget()
        val colorView = fb.getColorTextureView() ?: throw IllegalStateException("no color view")
        val depthView = fb.getDepthTextureView() ?: throw IllegalStateException("no depth view")
        // DynamicTransforms must carry BOTH the view rotation AND the camera-position/origin translate.
        // The camera ROTATION lives in RenderSystem's model-view (set by the world renderer) —
        // NOT in ctx.matrices(), which carries only the translate. The old Immediate path baked
        // matrices.last() into the verts AND let the RenderType set DynamicTransforms = getModelView,
        // so the shader applied Projection × getModelView × matrices.last() × raw. We bake only the
        // per-block translate, so DynamicTransforms = getModelView × matrices.last() reproduces that
        // exactly. (Setting it to matrices.last() alone — C2's first attempt — lost the rotation: blocks
        // translated with WASD but never rotated with the mouse.) mul(right, dest) writes getModelView ×
        // base into [MODEL_VIEW] without mutating the live model-view (getModelViewMatrixCopy hands us
        // a copy in 26.3; the old live getModelViewMatrix() accessor is gone).
        RenderSystem.getModelViewMatrixCopy().mul(matrices.last().pose(), MODEL_VIEW)
        val dynUniforms = RenderSystem.getDynamicUniforms()
        val seq = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        val enc = dev.createCommandEncoder()

        // Group the collected chunks by RenderType (at most entityCutout + blockTranslucentCull) so
        // each layer is one RenderPass with the pipeline + textures + uniform bound once. Reused
        // per-frame map (render thread only); the inner lists are reused too — cleared, not re-alloc'd.
        for (list in staticByLayer.values) list.clear()
        staticByLayer.clear()
        for (cb in chunks) {
            for ((layer, st) in cb.staticByLayer) {
                if (st.vertexCount == 0) continue
                staticByLayer.getOrPut(layer) { ArrayList() }.add(st)
            }
        }
        if (staticByLayer.isEmpty()) throw IllegalStateException("no static layers")

        // Two operations map a GPU buffer and are ILLEGAL while a RenderPass is open ("Close the
        // existing render pass before performing additional commands"), so they MUST run before any
        // createRenderPass: (1) DynamicGpuData.writeTransform maps the dynamic-uniform staging buffer to
        // write the model-view; (2) the sequential index buffer's getBuffer may GROW (map+upload) the
        // shared sequential quad index buffer. modelView is identical for every layer → write once +
        // grow once to the global max indexCount, then bind the pre-resolved slice/buffer inside each
        // pass (setUniform / setIndexBuffer / setVertexBuffer / drawIndexed are pass-safe — they bind,
        // not map).
        val mv = dynUniforms.writeTransform(MODEL_VIEW, COLOR_MOD, MODEL_OFFSET, TEX_IDENTITY)
        var globalMaxIdx = 0
        for ((_, list) in staticByLayer) for (st in list) if (st.indexCount > globalMaxIdx) globalMaxIdx = st.indexCount
        val idxBuffer = seq.getBuffer(globalMaxIdx)
        val idxType = seq.type()

        for ((layer, list) in staticByLayer) {
            var pass: RenderPass? = null
            try {
                // The layer's pipeline + texture bindings (Sampler0 = block atlas, overlay/lightmap) —
                // the same values vanilla's feature passes bind before `preparedType.draw`.
                val prepared = layer.prepare()
                pass = enc.createRenderPass(passName, colorView, Optional.empty<Vector4fc>(), depthView, OptionalDouble.empty())
                pass.setPipeline(RenderSystem.getCompiledPipeline(prepared.pipeline()))
                RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("DynamicTransforms", mv)
                for (t in prepared.textures()) pass.setUniform(t.name(), t.textureView(), t.sampler())
                pass.setIndexBuffer(idxBuffer, idxType)
                for (st in list) {
                    pass.setVertexBuffer(0, st.vb.slice())
                    pass.drawIndexed(st.indexCount, 1, 0, 0, 0)
                }
            } finally {
                try { pass?.close() } catch (_: Throwable) {}
            }
        }
    }

    /** Emit every precomputed quad of one baked chunk at its absolute recorded coord, grouped by
     *  RenderType (one submission per layer). The shared origin translate is already on [matrices];
     *  each block's translate is applied on the copied pose inside the geometry callback. This is the
     *  C2 **fail-open fallback** — the original per-frame quad emit, now a submit-node
     *  `submitCustomGeometry` (the 26.3 replacement for the removed `MultiBufferSource` bridge), used
     *  when [drawStaticAll] throws (or a chunk has no static buffer). Fail-open per layer + per block
     *  + per quad. */
    private fun emitQuads(
        cb: ChunkMesher.ChunkBlocks,
        matrices: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        for ((layer, blocks) in cb.byLayer) {
            try {
                collector.submitCustomGeometry(matrices, layer) { pose, vc ->
                    for (bq in blocks) {
                        try {
                            // The submitted pose is a snapshot of matrices.last(); translate a copy of it
                            // to this block's absolute recorded coord (the old push/translate/pop).
                            val p = pose.copy()
                            p.translate(bq.x.toFloat(), bq.y.toFloat(), bq.z.toFloat())
                            // Per-block quad state: tint (alpha 1) + fullbright + no overlay — the same
                            // values the old per-frame `vc.quad` call passed.
                            val qi = QuadInstance()
                            qi.setColor(ARGB.color((bq.r * 255f).toInt(), (bq.g * 255f).toInt(), (bq.b * 255f).toInt()))
                            qi.setLightCoords(FULL_LIGHT)
                            qi.setOverlayCoords(OverlayTexture.NO_OVERLAY)
                            for (q in bq.quads) {
                                try {
                                    vc.putBakedQuad(p, q, qi)
                                } catch (_: Throwable) {
                                    // skip one bad quad, keep the rest
                                }
                            }
                        } catch (_: Throwable) {
                            // skip one bad block, keep the rest
                        }
                    }
                }
            } catch (_: Throwable) {
                return
            }
        }
    }

    /** Drop the mesher cache (called from [dev.iustitia.replay.ReplayState.stop]). Idempotent. */
    fun free() = ChunkMesher.free()
}