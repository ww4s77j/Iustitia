package dev.iustitia.mixin

import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Accessor for `AvatarRenderState.playerName` — the **BELOW_NAME scoreboard score line**
 * (e.g. `"12 HP"`), populated by `AbstractClientPlayerEntity.getMannequinName()` only within ~10
 * blocks of the camera and only when the server registered a BELOW_NAME objective. It is NOT the
 * player's name (the name is `EntityRenderState.displayName`, accessed via [EntityRenderStateAccessor]).
 *
 * Used by the nametag prefix fallback: when a server hides the vanilla name (`displayName == null`
 * via team `nametagVisibility` — e.g. mcpvp.club), the only label the vanilla player renderer still
 * draws is this score line, so we prefix it as a tier cue. See
 * [PlayerEntityRendererMixin.iustitia_prefixNametagAtDraw].
 *
 * Target note: `@Accessor` does NOT walk the superclass chain, and `playerName` is declared on
 * `AvatarRenderState` itself (unlike `displayName`, which is declared on `EntityRenderState`
 * and so targeted by [EntityRenderStateAccessor] on that base class). A `AvatarRenderState`
 * instance IS-A `AvatarRenderState`, so casting a player render state to this accessor works
 * (the interface is added to the subclass). `playerName` is `@Nullable`, so the accessor uses `Component?`.
 */
// 26.3 hoisted the BELOW_NAME score line to the base state and renamed it `scoreText`
// (was `playerName` on the player state). @Accessor does not walk superclasses, so the
// target moves to the declaring base class; AvatarRenderState IS-A EntityRenderState.
@Mixin(EntityRenderState::class)
interface PlayerEntityRenderStateAccessor {

    @Accessor("scoreText")
    fun iustitia_getPlayerName(): Component?

    @Accessor("scoreText")
    fun iustitia_setPlayerName(value: Component?)
}