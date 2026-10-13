package dev.iustitia.mixin

import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Accessor for the MAIN nametag field `displayName` on the base [EntityRenderState]. This is the
 * team-colored nametag vanilla draws above an entity (`EntityRenderer.updateRenderState` sets it
 * to `getDisplayName(entity)` whenever the entity has a visible label). [PlayerEntityRendererMixin]
 * prepends the cheat-tier prefix here.
 *
 * WHY this targets `EntityRenderState` and NOT `AvatarRenderState`: `displayName` is
 * **declared** on `EntityRenderState` (obf `field_53337`), and inherited by `AvatarRenderState`.
 * Mixin `@Accessor` only matches fields declared on the mixin's target class — it does NOT walk
 * the superclass hierarchy — so an `@Accessor("displayName")` on `@Mixin(AvatarRenderState)`
 * fails at runtime with `InvalidAccessorException: No candidates were found matching
 * field_53337 ... in class_10055` (Loom's build-time remap does not catch this; it only fails at
 * mixin-apply). Targeting the declaring class fixes it. A `AvatarRenderState` instance
 * IS-A `EntityRenderState`, so casting a player render state to this accessor works (the
 * interface is added to the base class and inherited).
 */
@Mixin(EntityRenderState::class)
interface EntityRenderStateAccessor {
    // 26.3 renamed the main nametag field to `nameTag` (was `displayName`).
    @Accessor("nameTag")
    fun iustitia_getDisplayName(): Component?

    // 26.3 renamed the main nametag field to `nameTag` (was `displayName`).
    @Accessor("nameTag")
    fun iustitia_setDisplayName(value: Component)
}