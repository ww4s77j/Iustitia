package dev.iustitia.render

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import dev.iustitia.replay.ChunkSnapshot
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockColors
import com.mojang.blaze3d.vertex.BufferBuilder
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.client.renderer.texture.TextureAtlas
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.util.RandomSource

/**
 * Resolves a captured [ChunkSnapshot] into **per-block, face-culled, precomputed quads** grouped by
 * [RenderType], so [ChunkWorldRenderer] can draw the clip's solid world each frame by emitting those
 * quads directly — no per-frame model lookup, no buried faces. This replaces the old
 * `renderBlockAsEntity`-per-surface-block path (which emitted every block's full 6-face model every
 * frame, including faces buried against opaque neighbours — the ~380→40 FPS collapse on max-radius
 * clips) with a bake-once path that mirrors vanilla's own face-cull rule.
 *
 * ## Face cull = vanilla's rule (no holes)
 *
 * For each surface block, for each [BlockStateModelPart], we replicate
 * `ModelBlockRenderer.tesselateFlat` exactly:
 *  - per [Direction] `d`: `part.getQuads(d)` is emitted only when
 *    `Block.shouldRenderFace(thisState, neighborState, d)` is true (neighbour resolved from the raw
 *    snapshot; region-edge / air → air state → shouldRenderFace returns true → the face draws, closing
 *    the shell at the captured-region edge, as before).
 *  - `part.getQuads(null)` (the general / non-culled quads — torches, fences, plants, and any model
 *    detail without a cull face) is **always** emitted. This is the rule that prevents holes in
 *    non-cube blocks; a naive per-direction cull that skipped `getQuads(null)` would drop them.
 *
 * `Block.shouldRenderFace` (vanilla-correct: opaque + transparent + same-block rules, e.g.
 * glass-vs-glass, leaves-vs-leaves) replaces the old crude `isOpaqueAt` approximation for the
 * **per-face** decision. `isOpaqueAt` is retained only for the block-level surface-shell skip (a block
 * whose 6 neighbours are all opaque is fully interior and never seen — pure overdraw).
 *
 * ## Lazy per-chunk baking (unchanged from v1.2.2)
 *
 * Each chunk bakes on demand the first time it enters the camera's render-distance ring, and
 * [ChunkWorldRenderer] only bakes a small per-frame block budget of the nearest unbaked in-range
 * chunks — so there's no single bake spike, far chunks only bake if you fly toward them, and a
 * scrolling-in chunk bakes alone. See [ChunkWorldRenderer] for the budget + ring walk.
 *
 * Neighbour lookups (both the block-level skip and the per-face cull) read the **raw snapshot** via
 * [ChunkSnapshot.nameAt] (O(1)), NOT the baked cache — so culling is correct regardless of which
 * chunks have baked yet. Bake order has no effect on correctness.
 *
 * ## Cache + fail-open
 *
 * Per-name caches: `BlockState` (`stateCache`), tint ARGB (`tintCache`)
 * — block names repeat heavily across chunks, so these persist across chunk bakes and are a big win.
 * The per-quad RenderType is derived from each `BakedQuad`'s own `ChunkSectionLayer`
 * (`materialInfo().layer()` — the 26.3 model; the old per-block `ItemBlockRenderTypes` lookup is
 * gone) and is memoized inside `RenderTypes`. A new snapshot (new clip) clears the caches
 * via [ensureSnapshot]. `BuiltInRegistries.BLOCK` is a
 * DefaultedRegistry → an unknown id resolves to AIR (renders as nothing, counts as a transparent
 * neighbour) — the fail-open behaviour we want. [free] drops everything (called from
 * [dev.iustitia.replay.ReplayState.stop]). All wrapped fail-open so a render/bake error degrades
 * (missing blocks / no chunk world) rather than crashing the client.
 *
 * ## v1 limits (documented follow-ups, unchanged)
 *
 * Block **properties** — since v7 the palette key carries the full state string for stateful blocks
 * and [stateFor] parses the EXACT recorded state, so a stairs renders in its recorded facing (not the
 * default), and non-full/transparent blocks feed [shouldDrawSide] their real shape (neighbours behind
 * glass/stairs/slabs render). The block-level surface-shell skip uses [BlockState.isSolidRender]
 * (NOT [BlockState.canOcclude], which is true for opaque-material slabs/stairs and would wrongly skip a
 * block fully surrounded by them → the "missing texture behind slabs/stairs" bug). Pre-v7 clips have
 * only bare-id palette entries → default state (today's behaviour). Block **entities** (chest contents,
 * sign text, banners) and **fluids** (`INVISIBLE` render type, skipped here) are still not rendered.
 * Lighting stays fullbright (no AO/skylight). A full GPU static-mesh bake (`GpuBuffer` +
 * `VertexBufferManager`, one upload / one draw call per chunk per frame) is the perf follow-up if this
 * win is insufficient.
 */
object ChunkMesher {

    /** One surface block's precomputed, face-culled quads at an absolute recorded coord, with the
     *  block's tint (r,g,b floats in 0..1; alpha is always 1 — replays are fullbright). Built once at
     *  bake time; [ChunkWorldRenderer] emits these via `VertexConsumer.putBakedQuad` (the 26.3 quad
     *  emission — the old `VertexConsumer.quad` is gone), either into the static bake buffer or the
     *  submit-node custom-geometry fallback. */
    data class BlockQuads(
        val x: Int, val y: Int, val z: Int,
        val r: Float, val g: Float, val b: Float,
        val quads: List<BakedQuad>,
    )

    /** One captured chunk's surface quads baked into a **static GPU vertex buffer** (the C2 perf
     *  rework). Baked ONCE at bake time into the layer's vertex format
     *  (`POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL`, `PrimitiveTopology.QUADS`) with **absolute
     *  recorded coords** already baked into the vertices (each block's recorded coord was passed as
     *  the `putBlockBakedQuad` x/y/z offset at bake time, so no per-frame per-block matrix is needed —
     *  the renderer binds one model-view matrix per frame as the `"DynamicTransforms"` uniform).
     *  [vertexCount] = 4 × quad count; [indexCount] = 6 × quad count (triangulated at draw time by
     *  the shared sequential quad index buffer). [vb] is a `GpuBuffer` (USAGE_VERTEX | USAGE_COPY_DST)
     *  owned here, closed in [free]. A bake failure omits the layer from [ChunkBlocks.staticByLayer]
     *  entirely (so [vb] is always non-null when present) — the renderer then falls back to
     *  [ChunkBlocks.byLayer]'s per-frame `putBakedQuad` emit for that layer. Fail-open. */
    data class StaticChunkLayer(val vb: GpuBuffer, val vertexCount: Int, val indexCount: Int)

    /** The precomputed quads of one captured chunk, grouped by [RenderType] (so the renderer draws
     *  each layer once per frame) + a [blockCount] (the number of surface blocks, for
     *  [ChunkWorldRenderer]'s per-frame lazy-bake budget — a chunk's bake cost scales with its
     *  surface-block count) + the baked-block AABB (`minX..maxX` etc, in **recorded** coords, true
     *  extent — `max` is `maxBlockCoord + 1` so the box covers the full 1-block cell). The AABB lets
     *  [ChunkWorldRenderer] cull chunks fully behind the camera plane without popping (an 8-corner
     *  test against the camera forward; a chunk is only dropped when EVERY corner is behind the
     *  camera, so a partly-in-front chunk always renders). All zero when the chunk has no blocks.
     *
     *  [staticByLayer] is the C2 static-GPU-mesh path (one [StaticChunkLayer] per layer; the renderer
     *  draws these via its own `RenderPass` with no per-frame re-emit/upload). [byLayer] is kept as the
     *  fail-open fallback — the per-frame `putBakedQuad` emit used when a chunk has no static buffer (GPU
     *  bake failed) or when the whole static-draw path throws for a frame. */
    data class ChunkBlocks(
        val chunkX: Int, val chunkZ: Int,
        val byLayer: Map<RenderType, List<BlockQuads>>,
        val staticByLayer: Map<RenderType, StaticChunkLayer>,
        val blockCount: Int,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
    )

    @Volatile private var snapRef: ChunkSnapshot? = null
    @Volatile private var snapIndex: HashMap<Long, ChunkSnapshot.ChunkRec>? = null
    private var baked: LinkedHashMap<Long, ChunkBlocks> = LinkedHashMap()

    /** Fullbright light coord (LightTexture.MAX_LIGHT_COORDINATE = 0xF000F0) — the same
     *  flat lighting the renderer applies; baked into the static buffer at bake time. */
    private const val FULL_LIGHT = 0xF000F0

    /** Initial CPU capacity for the per-chunk bake [ByteBufferBuilder] (bytes). The allocator grows on
     *  demand; this just bounds regrow count. A worst-case dense arena chunk is ~15–30k quads ×
     *  4 verts × 36 B/vert (POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL) ≈ a few MB. */
    private const val STATIC_ALLOC_CAPACITY = 2_097_152
    private var stateCache: HashMap<String, BlockState?> = HashMap(256)
    private var tintCache: HashMap<String, Int> = HashMap(256)
    private val rand: RandomSource = RandomSource.create()
    private val directions: Array<Direction> = Direction.entries.toTypedArray()

    /** Chunk-coord → long key, delegating to the one definition of the encoding
     *  ([dev.iustitia.replay.BlockDeltaBuffer.chunkKey]) so the bake cache and the snapshot index it
     *  is keyed against can never drift apart. This local spelling stays because it is called ten
     *  times below. */
    private fun key(chunkX: Int, chunkZ: Int): Long = dev.iustitia.replay.BlockDeltaBuffer.chunkKey(chunkX, chunkZ)

    /**
     * Key the mesher to [snap]. A **different snapshot reference** (a new clip, or re-play of a reloaded
     * clip) clears the bake cache + all per-name caches + rebuilds the chunk index — so a new playclip
     * always starts a fresh lazy bake. Same reference is a no-op. Reassigns fresh map instances (rather
     * than `.clear()`) so a concurrent render thread holding the old `baked` ref keeps using a
     * consistent stale map instead of throwing `ConcurrentModificationException` — fail-open under a
     * stop/free race.
     */
    fun ensureSnapshot(snap: ChunkSnapshot) {
        if (snapRef === snap && snapIndex != null) return
        snapRef = snap
        closeStaticBaked(baked); baked = LinkedHashMap()
        stateCache = HashMap(256)
        tintCache = HashMap(256)
        val m = HashMap<Long, ChunkSnapshot.ChunkRec>(snap.chunks.size)
        for (c in snap.chunks) m[key(c.chunkX, c.chunkZ)] = c
        snapIndex = m
    }

    /** The captured [ChunkSnapshot.ChunkRec] at chunk coord (cx,cz), or null if the snapshot has no
     *  chunk there. Used by the renderer to know which in-range coords actually have data to bake. */
    fun chunkRecAt(cx: Int, cz: Int): ChunkSnapshot.ChunkRec? = snapIndex?.get(key(cx, cz))

    /** The already-baked [ChunkBlocks] at (cx,cz), or null if that chunk hasn't been baked yet (or the
     *  snapshot has no chunk there). Read every frame by the renderer. */
    fun bakedChunk(cx: Int, cz: Int): ChunkBlocks? = baked[key(cx, cz)]

    /**
     * The chunk keys whose bake must be redone after a block changes at (x,y,z): the containing chunk
     * plus any neighbour when the cell sits on a chunk border (a border block's face-cull depends on
     * the neighbour chunk's blocks). Ported from SnapClip; used by the block-delta overlay so a replayed
     * world edit re-meshes the affected chunk(s) instead of showing the stale baked mesh.
     */
    fun invalidateAround(x: Int, y: Int, z: Int): Set<Long> {
        val cx = x shr 4; val cz = z shr 4
        val lx = x and 15; val lz = z and 15
        val out = HashSet<Long>(5)
        out.add(key(cx, cz))
        if (lx == 0) out.add(key(cx - 1, cz))
        if (lx == 15) out.add(key(cx + 1, cz))
        if (lz == 0) out.add(key(cx, cz - 1))
        if (lz == 15) out.add(key(cx, cz + 1))
        return out
    }

    /**
     * Drop + close the baked chunks at [keys] (so the next frame lazy-bakes them from the current
     * snapshot + overlay). Closes the static `GpuBuffer`s before removing, so a delta-driven
     * re-mesh doesn't orphan GL buffers. Fail-open per chunk.
     */
    fun invalidateChunks(keys: Set<Long>) {
        try {
            for (k in keys) {
                val cb = baked.remove(k) ?: continue
                closeChunkBlocks(cb)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Bake one chunk's face-culled quads **on demand** (the lazy-bake primitive). Idempotent — returns
     * the cached [ChunkBlocks] if (cx,cz) was already baked. Returns null when the snapshot has no
     * chunk at (cx,cz), or on any throw (fail-open → renderer skips that chunk, keeps going).
     * [ensureSnapshot] is called first so a bare [bakeChunk] still works.
     */
    fun bakeChunk(snap: ChunkSnapshot, cx: Int, cz: Int): ChunkBlocks? = try {
        ensureSnapshot(snap)
        val k = key(cx, cz)
        baked[k]?.let { return it }
        val rec = snapIndex?.get(k) ?: return null
        val byLayer = bakeChunkBlocks(snap, rec)
        val blockCount = byLayer.values.sumOf { it.size }
        // Baked-block AABB (recorded coords, true extent: max = coord+1). One pass over the baked
        // blocks at bake time so the renderer can do a pop-free 8-corner behind-camera cull each frame.
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        if (blockCount > 0) {
            for (bqs in byLayer.values) for (bq in bqs) {
                if (bq.x < minX) minX = bq.x
                if (bq.y < minY) minY = bq.y
                if (bq.z < minZ) minZ = bq.z
                if (bq.x > maxX) maxX = bq.x
                if (bq.y > maxY) maxY = bq.y
                if (bq.z > maxZ) maxZ = bq.z
            }
            maxX++; maxY++; maxZ++
        } else {
            minX = 0; minY = 0; minZ = 0; maxX = 0; maxY = 0; maxZ = 0
        }
        // C2: bake each layer's quads into a static GPU vertex buffer once (absolute recorded coords
        // already baked in, so no per-frame per-block matrix). Fail-open per layer — a GPU-bake throw
        // leaves that layer out of staticByLayer and the renderer falls back to byLayer's putBakedQuad
        // emit.
        val staticByLayer = try { bakeStatic(byLayer) } catch (_: Throwable) { emptyMap() }
        val cb = ChunkBlocks(rec.chunkX, rec.chunkZ, byLayer, staticByLayer, blockCount, minX, minY, minZ, maxX, maxY, maxZ)
        baked[k] = cb
        cb
    } catch (_: Throwable) {
        null
    }

    /** Compute the face-culled quads of one chunk, grouped by RenderType. Block names + tint resolve
     *  through the shared per-name caches; each quad's RenderType comes from its own 26.3
     *  `ChunkSectionLayer` (`materialInfo().layer()`). */
    private fun bakeChunkBlocks(snap: ChunkSnapshot, rec: ChunkSnapshot.ChunkRec): Map<RenderType, List<BlockQuads>> {
        val byLayer = HashMap<RenderType, MutableList<BlockQuads>>()
        val mc = Minecraft.getInstance()
        val blockColors = mc.getBlockColors()
        val baseX = rec.chunkX shl 4
        val baseZ = rec.chunkZ shl 4
        for (sec in rec.sections) {
            val baseY = sec.sectionY shl 4
            val pw = if (sec.palette.size <= 256) 1 else 2
            for (entry in 0 until 4096) {
                // Local cell coords (iteration order y-major → z → x, inverse of ChunkCapture).
                val lx = entry and 15
                val lz = (entry shr 4) and 15
                val ly = entry shr 8
                val x = baseX + lx
                val y = baseY + ly
                val z = baseZ + lz
                // Overlay first (SnapClip port): a block change recorded during the replay outranks the
                // captured palette — a placed block (palette says air) bakes, a broken block (overlay
                // says air) is skipped. No-op when no overlay is active (the common live-clip case).
                val name = snap.overlayAt(x, y, z) ?: (readIndex(sec, entry, pw) ?: continue)
                if (name == "minecraft:air") continue
                val st = stateFor(name) ?: continue
                // Fluids + invisible-render-type blocks render nothing (matches the old
                // renderBlockAsEntity self-skip). MODEL-render-shape blocks proceed via their model.
                if (st.renderShape == RenderShape.INVISIBLE) continue
                // Block-level surface-shell skip: all 6 neighbours opaque ⇒ fully interior ⇒ never seen.
                if (isOpaqueAt(snap, x + 1, y, z) &&
                    isOpaqueAt(snap, x - 1, y, z) &&
                    isOpaqueAt(snap, x, y + 1, z) &&
                    isOpaqueAt(snap, x, y - 1, z) &&
                    isOpaqueAt(snap, x, y, z + 1) &&
                    isOpaqueAt(snap, x, y, z - 1)
                ) continue
                // Block model: 26.3 resolves states through ModelManager.getBlockStateModelSet() and
                // the model fills a caller-provided part list (the old BlockRenderDispatcher.getModel
                // + model.getParts(rand) pair are gone).
                val model = try { mc.getModelManager().getBlockStateModelSet().get(st) } catch (_: Throwable) { null } ?: continue
                val parts = ArrayList<BlockStateModelPart>(1)
                try { model.collectParts(rand, parts) } catch (_: Throwable) {}
                if (parts.isEmpty()) continue
                val tint = tintFor(name, st, blockColors)
                val r = ((tint shr 16) and 0xFF) / 255f
                val g = ((tint shr 8) and 0xFF) / 255f
                val b = (tint and 0xFF) / 255f
                // One block's quads can straddle layers (translucent + cutout parts), so partition
                // them by the quad's own section layer → RenderType.
                val quadsByType = HashMap<RenderType, MutableList<BakedQuad>>(2)
                for (part in parts) {
                    // Per-direction face quads: emit only when vanilla's shouldDrawSide says the face
                    // is visible (neighbour not opaque-and-same-block). This is the buried-face cull.
                    for (d in directions) {
                        val faceQuads: List<BakedQuad> = try { part.getQuads(d) } catch (_: Throwable) { emptyList() }
                        if (faceQuads.isEmpty()) continue
                        if (!shouldDrawSide(st, snap, x, y, z, d)) continue
                        for (q in faceQuads) quadsByType.getOrPut(renderTypeFor(q)) { ArrayList() }.add(q)
                    }
                    // General / non-culled quads (getQuads(null)) — torches, fences, plants, model
                    // detail with no cull face. ALWAYS emitted (vanilla renderFlat does the same) —
                    // skipping these would punch holes in non-cube blocks.
                    val generalQuads: List<BakedQuad> = try { part.getQuads(null) } catch (_: Throwable) { emptyList() }
                    for (q in generalQuads) quadsByType.getOrPut(renderTypeFor(q)) { ArrayList() }.add(q)
                }
                for ((layer, quads) in quadsByType) {
                    if (quads.isEmpty()) continue
                    byLayer.getOrPut(layer) { ArrayList() }.add(BlockQuads(x, y, z, r, g, b, quads))
                }
            }
        }
        return byLayer
    }

    /**
     * C2 bake: turn one chunk's per-layer [BlockQuads] into **static GPU vertex buffers** (one per
     * layer). Each layer is emitted ONCE into a `BufferBuilder` (`PrimitiveTopology.QUADS`, the
     * layer's own `VertexFormat` — the entity block format, `POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL`)
     * with each block's absolute recorded coord passed as the `putBlockBakedQuad` x/y/z offset, so the
     * baked vertices carry absolute positions and the renderer needs no per-frame per-block matrix
     * (just one model-view uniform per frame). The built `ByteBuffer` is uploaded once via
     * `RenderSystem.getDevice().createBuffer` (`USAGE_VERTEX | USAGE_COPY_DST`), then the CPU buffer is
     * released. This is the same quad-emission work the renderer used to do **per frame**, now done
     * **once per chunk** — moving the ~90% per-frame emission cost to a one-time, lazy-budgeted bake.
     * Per-layer fail-open: a throw for one layer leaves it absent from the result and the renderer
     * falls back to that layer's [byLayer].
     */
    private fun bakeStatic(byLayer: Map<RenderType, List<BlockQuads>>): Map<RenderType, StaticChunkLayer> {
        val out = HashMap<RenderType, StaticChunkLayer>()
        if (byLayer.isEmpty()) return out
        val dev = try { RenderSystem.getDevice() } catch (_: Throwable) { null } ?: return out
        // Reused per-block quad state (tint + fullbright + no overlay — replays are fullbright, the
        // same values the old per-frame `vc.quad` call passed); `putBlockBakedQuad` copies it per vertex.
        val qi = QuadInstance()
        for ((layer, blocks) in byLayer) {
            try {
                if (blocks.isEmpty()) continue
                val alloc = ByteBufferBuilder(STATIC_ALLOC_CAPACITY)
                try {
                    val bb = BufferBuilder(alloc, layer.primitiveTopology(), layer.format())
                    for (bq in blocks) {
                        try {
                            qi.setColor(ARGB.color((bq.r * 255f).toInt(), (bq.g * 255f).toInt(), (bq.b * 255f).toInt()))
                            qi.setLightCoords(FULL_LIGHT)
                            qi.setOverlayCoords(OverlayTexture.NO_OVERLAY)
                            for (q in bq.quads) {
                                try {
                                    bb.putBlockBakedQuad(bq.x.toFloat(), bq.y.toFloat(), bq.z.toFloat(), q, qi)
                                } catch (_: Throwable) {
                                    // skip one bad quad, keep the rest of the chunk
                                }
                            }
                        } catch (_: Throwable) {
                            // skip one bad block, keep the rest of the chunk
                        }
                    }
                    val built = bb.build() ?: continue
                    try {
                        val bytes = built.vertexBuffer()
                        val vb = dev.createBuffer(
                            java.util.function.Supplier<String> { "iustitia-chunk-static" },
                            GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST,
                            bytes,
                        )
                        try {
                            val vertexCount = built.drawState().vertexCount()
                            // QUADS topology: 4 verts/quad → 6 indices/quad (triangulated at draw by the
                            // shared sequential quad index buffer).
                            out[layer] = StaticChunkLayer(vb, vertexCount, vertexCount / 4 * 6)
                        } catch (t: Throwable) {
                            // vb is ours until the [out] assignment hands ownership to the layer —
                            // a throw in between (drawState, HashMap put) leaked the GPU
                            // buffer: nothing owned it, so nothing closed it on teardown. Close it
                            // here and rethrow into the per-layer fail-open catch below.
                            try { vb.close() } catch (_: Throwable) {}
                            throw t
                        }
                    } finally {
                        try { built.close() } catch (_: Throwable) {}
                    }
                } finally {
                    try { alloc.close() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                // fail-open for this layer: leave it absent, renderer falls back to byLayer emit
            }
        }
        return out
    }

    /**
     * Vanilla face-cull rule (mirrors `ModelBlockRenderer.shouldRenderFace` with the cull flag set):
     * a face draws when [Block.shouldRenderFace] returns true for the neighbour in direction [d]. The
     * neighbour state resolves from the raw snapshot; region-edge / air → AIR default state →
     * shouldRenderFace returns true → the face draws (closes the shell at the captured-region edge).
     * Fail-open to true (draw the face) on any throw — drawing an extra face is harmless, dropping one
     * would leave a hole.
     */
    private fun shouldDrawSide(state: BlockState, snap: ChunkSnapshot, x: Int, y: Int, z: Int, d: Direction): Boolean = try {
        val nName = snap.nameAt(x + d.stepX, y + d.stepY, z + d.stepZ)
        val nState = if (nName == null || nName == "minecraft:air") Blocks.AIR.defaultBlockState() else stateFor(nName)
        if (nState == null) true else Block.shouldRenderFace(state, nState, d)
    } catch (_: Throwable) { true }

    /** Resolve a palette name to its BlockState, memoized in [stateCache]. null = bad name / throw.
     *
     *  v7 palette keys carry the EXACT recorded state when the block has properties —
     *  `"minecraft:stairs[facing=east,half=top,shape=straight]"` (BlockStateParser-compatible form,
     *  written by [dev.iustitia.replay.ChunkCapture.stateKey]). A key with `[` is parsed via
     *  [BlockStateParser.parseForBlock] into the precise state → correct facing/shape/half (fixes the v1
     *  "stairs/slabs render in their default facing" bug) AND feeds [shouldDrawSide]/[isOpaqueAt] the
     *  real neighbour shape (fixes the "blocks behind non-full/transparent blocks don't render" cull
     *  bug). A propertyless key (`"minecraft:stone"`) takes the fast path (today's defaultBlockState
     *  lookup). Pre-v7 clips have only propertyless/bare-id keys → identical to today. Fail-open: a bad
     *  property value (CommandSyntaxException) falls back to the bare-id default block state (today's behaviour — the
     *  block renders in its default orientation instead of not at all); a bad id → null (skip / treat
     *  as transparent, same as today's AIR-from-DefaultedRegistry result).
     *
     *  NB: BuiltInRegistries.BLOCK is a DefaultedRegistry, so get(unknownId) returns AIR (never null) — an
     *  unknown block resolves to air, which renders as nothing and is a transparent neighbour —
     *  exactly the fail-open behaviour we want, so no special unknown-skip needed. */
    private fun stateFor(name: String): BlockState? {
        // `containsKey`, NOT `getOrPut`. Kotlin's getOrPut keys off `get(name) == null`, so a cached
        // null reads back as "absent" and the resolver re-runs — which meant an unresolvable palette
        // name was re-parsed on every single access for the whole clip, not once. A null here is a real
        // answer ("this name resolves to nothing, skip it"), so the miss is memoized like any other.
        if (stateCache.containsKey(name)) return stateCache[name]
        val resolved = resolveState(name)
        stateCache[name] = resolved
        return resolved
    }

    /** The uncached half of [stateFor]: resolve a palette name once, no memoization. */
    private fun resolveState(name: String): BlockState? = try {
        if ('[' in name) {
            // BuiltInRegistries.BLOCK is a DefaultedRegistry<Block> which IS-A HolderLookup<Block>
            // (Registry extends HolderLookup.RegistryLookup), so it's passed directly as the lookup the
            // parser resolves block ids + properties against.
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState()
        } else {
            val id = Identifier.tryParse(name) ?: return null
            BuiltInRegistries.BLOCK.getValue(id).defaultBlockState()
        }
    } catch (_: Throwable) {
        // fall back to the bare-id default state (today's behaviour) — a bad property value still
        // renders the block in its default orientation instead of skipping it entirely.
        val bare = name.substringBefore('[')
        val id = Identifier.tryParse(bare) ?: return null
        try { BuiltInRegistries.BLOCK.getValue(id).defaultBlockState() } catch (_: Throwable) { null }
    }

    /** Resolve a block's tint colour (ARGB int) for tint index 0, memoized in [tintCache]. The 26.3
     *  replacement for the old `BlockColors.getColor(state, null, null, 0)` call: the index-0
     *  [net.minecraft.client.color.block.BlockTintSource]'s no-world colour (the non-biome default for
     *  grass etc.; a no-tint block has no source → null → white, matching the old -1/0xFFFFFF result).
     *  Fail-open to white. */
    private fun tintFor(name: String, st: BlockState, blockColors: BlockColors): Int = tintCache.getOrPut(name) {
        try { blockColors.getTintSource(st, 0)?.color(st) ?: 0xFFFFFF } catch (_: Throwable) { 0xFFFFFF }
    }

    /** The RenderType a quad with section layer [layer] draws through. 26.3 dropped the per-block
     *  `ItemBlockRenderTypes.getEntityBlockLayer` lookup — every [BakedQuad] now carries its own
     *  [ChunkSectionLayer] in `materialInfo()`. SOLID and CUTOUT share the alpha-tested cutout type
     *  (harmless for opaque textures) so a chunk still batches into at most two layers — cutout +
     *  translucency — exactly the two-layer split the C2 path always drew. Fail-open: an unknown /
     *  missing layer draws as cutout. */
    private fun renderTypeFor(quad: BakedQuad): RenderType = try {
        if (quad.materialInfo()?.layer() == ChunkSectionLayer.TRANSLUCENT) {
            RenderTypes.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
        } else {
            RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS)
        }
    } catch (_: Throwable) {
        RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS)
    }

    /** Read palette index [entry] (0..4095) → palette name, honouring the 1-vs-2 byte width. Fail-open null. */
    private fun readIndex(sec: ChunkSnapshot.SectionRec, entry: Int, pw: Int): String? = try {
        if (pw == 1) sec.palette.getOrNull(sec.data[entry].toInt() and 0xFF)
        else {
            val i = entry * 2
            if (i + 1 >= sec.data.size) return null
            val idx = ((sec.data[i].toInt() and 0xFF) shl 8) or (sec.data[i + 1].toInt() and 0xFF)
            sec.palette.getOrNull(idx)
        }
    } catch (_: Throwable) { null }

    /** True when the block at (x,y,z) is a **full opaque cube** (used for the block-level surface-shell
     *  skip only — the per-face cull uses [shouldDrawSide]/Block.shouldRenderFace, which is shape-aware).
     *  `null` (outside the captured region) and air count as NOT opaque. Reads the raw snapshot;
     *  fail-open to not-opaque.
     *
     *  NB: this MUST use [BlockState.isSolidRender], NOT [BlockState.canOcclude]. `canOcclude` is the
     *  raw material flag — TRUE for stone/oak **slabs and stairs** (opaque material) even though they
     *  are not full cubes. Using `canOcclude` here would wrongly skip a block whose 6 neighbours are all
     *  slabs/stairs (each `canOcclude==true`) even though parts of its faces are visible through the
     *  non-full neighbours' open halves → the intermittent "missing texture behind slabs/stairs" bug.
     *  `isSolidRender` is true only when the neighbour both occludes AND is a full 1×1×1 cube, so a
     *  block is skipped only when it is genuinely fully buried (the per-face cull would draw nothing
     *  anyway). Slabs/stairs/glass/leaves all return false → never trigger the skip → the block bakes
     *  and the shape-aware per-face cull handles it correctly. */
    private fun isOpaqueAt(snap: ChunkSnapshot, x: Int, y: Int, z: Int): Boolean = try {
        val n = snap.nameAt(x, y, z) ?: return false
        if (n == "minecraft:air") return false
        val st = stateFor(n) ?: return false
        st.isSolidRender
    } catch (_: Throwable) { false }

    /** Drop the cache (called from [dev.iustitia.replay.ReplayState.stop]). Idempotent + fail-open.
     *  Closes the static `GpuBuffer`s owned by the baked chunks BEFORE dropping the cache (reassigning
     *  a fresh map would orphan the GL buffers). Reassigns fresh instances so a concurrent render
     *  thread can't see a half-cleared map. */
    fun free() {
        try {
            closeStaticBaked(baked)
            snapRef = null
            snapIndex = null
            baked = LinkedHashMap()
            stateCache = HashMap(256)
            tintCache = HashMap(256)
        } catch (_: Throwable) {}
    }

    /** Close every static `GpuBuffer` in a baked-chunks map (fail-open per buffer). Used by
     *  [ensureSnapshot] + [free] before dropping/replacing the map, so GL buffers aren't orphaned. */
    private fun closeStaticBaked(map: Map<Long, ChunkBlocks>) {
        for (cb in map.values) closeChunkBlocks(cb)
    }

    /** Close one baked chunk's static `GpuBuffer`s (fail-open per buffer). */
    private fun closeChunkBlocks(cb: ChunkBlocks) {
        for (st in cb.staticByLayer.values) {
            try { st.vb.close() } catch (_: Throwable) {}
        }
    }
}