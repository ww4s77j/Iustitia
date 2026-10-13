package dev.iustitia.render

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.world.entity.player.PlayerSkin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-UUID player-skin cache for the replay/clip ghosts (Phase 2 polish). Resolves the REAL skin of
 * every tracked player so a ghost wears the player's actual skin instead of a forced Steve.
 *
 * ## How it resolves
 *
 * Vanilla already loads every other player's skin into the tab-list [net.minecraft.client.multiplayer.PlayerInfo]
 * on join (async Mojang fetch, wrapped by [net.minecraft.client.resources.SkinManager]).
 * [PlayerInfo.getSkinTextures] returns the loaded [PlayerSkin], or the per-UUID **default**
 * ([DefaultPlayerSkin.getSkinTextures] — Steve/Alex chosen by UUID hash) until the real skin lands.
 * So resolution is a synchronous read of an entry vanilla keeps warm — NO direct skin-provider call,
 * NO extra network, NO thread we spin up. We just ask the tab list.
 *
 * "Is the real skin loaded yet?" is answered by [PlayerSkin] equality: it is a `record`, so
 * `skin != default` is a clean value comparison. While they're equal (still the default placeholder),
 * we keep re-asking each render frame; the moment the real skin lands they differ and we lock it in.
 * A streak cap ([RESOLVE_FRAMES]) bounds the case of a player who genuinely uses the default skin
 * (they never differ) — after ~3s we accept whatever we have so the cache stops re-querying. A player
 * absent from the tab list (a clip of someone who logged off / out of range) is locked to the default
 * immediately — there's no entry to query.
 *
 * ## Threading + bounds
 *
 * [resolve] runs on the render thread (from [ReplayRenderer.drawModel]); [reset] runs on the client
 * thread (from [dev.iustitia.Iustitia.resetAll] on world change — skins are per-server). The map is a
 * [ConcurrentHashMap]; a clear racing a resolve at worst recreates one holder next frame. Unbounded
 * growth is bounded in practice by the player count of the servers you visit; [reset] trims on
 * world change. All paths fail-open to [DefaultPlayerSkin.getSteve] — a bad lookup never breaks a frame.
 *
 * Display-only: adds NO detection.
 */
object ReplaySkins {

    private class Holder(var skin: PlayerSkin, var resolved: Boolean, var streak: Int)

    private val cache = ConcurrentHashMap<UUID, Holder>()

    /** Re-ask the tab list this many frames before locking in a skin that still equals the default
     *  (the player may simply use the default skin — they never differ, so we cap to stop querying). */
    private const val RESOLVE_FRAMES = 60

    /**
     * The [PlayerSkin] to render for [uuid]. Fast path: cached + resolved → one map read. Slow path
     * (first frames after a replay starts for a given player, or an uncached UUID): query the tab list.
     * Fail-open to the Steve default on any throw so a ghost always renders.
     */
    fun resolve(uuid: UUID): PlayerSkin {
        try {
            val h = cache[uuid]
            if (h != null && h.resolved) return h.skin
            return resolveSlow(uuid, h)
        } catch (_: Throwable) {
            return DefaultPlayerSkin.getDefaultSkin()
        }
    }

    private fun resolveSlow(uuid: UUID, existing: Holder?): PlayerSkin {
        val default = DefaultPlayerSkin.get(uuid)
        val h = existing ?: cache.computeIfAbsent(uuid) { Holder(default, false, 0) }
        if (h.resolved) return h.skin

        val mc = Minecraft.getInstance()
        val entry = try { mc.connection?.getPlayerInfo(uuid) } catch (_: Throwable) { null }
        if (entry == null) {
            // Not in the tab list (offline / out of range) — lock in the per-UUID default now.
            h.skin = default; h.resolved = true
            return default
        }
        val skin = try { entry.skin } catch (_: Throwable) { default }
        h.skin = skin
        // PlayerSkin is a record → != is a clean value compare. Differs from the default ⇒ real skin landed.
        if (skin != default) {
            h.resolved = true
        } else {
            h.streak++
            if (h.streak >= RESOLVE_FRAMES) h.resolved = true
        }
        return skin
    }

    /** Clear the cache (wired to [dev.iustitia.Iustitia.resetAll] on world/dimension change). */
    fun reset() {
        try { cache.clear() } catch (_: Throwable) {}
    }
}