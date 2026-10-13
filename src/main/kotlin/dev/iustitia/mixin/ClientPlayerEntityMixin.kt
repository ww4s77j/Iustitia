package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.player.ClientInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Input
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Suppresses the local player's walking while ANY replay/playclip is active (every cam mode), so
 * spectating a rewind/clip never moves the live player. Without this, both the camera and the
 * player move on WASD — the camera would just track the player instead of free-flying through the
 * clip's solid world (incl. underground).
 *
 * ## Mechanism
 *
 * `LocalPlayer.tickMovement()` reads `input.keyPresses` (the [Input] record of held
 * movement keys, refreshed each tick by `ClientInput.tick()` at the end of `tickMovement`) and applies it
 * to the player's velocity. Injecting at `HEAD` and zeroing `input.keyPresses = Input.EMPTY`
 * makes that read see no held keys, so the player does not walk/jump/sneak this tick. The keys
 * themselves (the vanilla `KeyMapping`s) stay pressed — [dev.iustitia.Iustitia.onClientTick] still
 * reads them to advance the camera, and `ClientInput.tick()` at the end re-reads the keys for next tick, so
 * when FREECAM ends the player resumes normally. Verified against the 26.3 client jar: `input` is a
 * `public ClientInput input` field on `LocalPlayer`, `Input` is the movement record with a
 * `public static Input EMPTY` zeroed instance, and `tickMovement` is the method that consumes
 * it.
 *
 * ## Why zero [keyPresses] and not the whole [ClientInput]
 *
 * We mutate only the record field the movement read consumes; we never replace `input` itself or
 * touch `moveVector`/`ClientInput.tick()`. The next `ClientInput.tick()` rebuilds `keyPresses` from the
 * still-held keys, so there is no stuck-state risk and no keybinding side effects.
 *
 * ## Gate + fail-open
 *
 * Cancels nothing — this is an `@Inject` (not cancellable) that overwrites a field. It only acts when
 * `ReplayState.active`; every other tick is a no-op (one volatile read). The whole body is
 * try/caught (project fail-open posture): a throw leaves the player's input untouched
 * (worst case = the player walks a tick during a freecam, never a crash). Camera-only — no packets,
 * no server-side movement.
 *
 * Runtime-only-verifiable (per project convention for input-path mixins): the `tickMovement` target
 * + the `input` field are verified against the named jar; `defaultRequire: 1` means a mismatch fails
 * launch — the build can't confirm mixin-apply, only a live client run can.
 */
@Mixin(LocalPlayer::class)
class ClientPlayerEntityMixin {

    @field:Shadow
    var input: ClientInput? = null

    @Inject(method = ["aiStep"], at = [At("HEAD")])
    private fun iustitia_suppressReplayMovement(ci: CallbackInfo) {
        try {
            // During any active replay/playclip (every cam mode) the user is spectating a rewind/clip,
            // so their walking inputs must not move the live player. Zeroing keyPresses here makes the
            // vanilla movement read see no held keys; the packet-cancel mixin (ClientConnectionMixin)
            // is the belt-and-suspenders for movement + the sole mechanism for break/place/attack/etc.
            if (ReplayState.active) {
                // Input.EMPTY is the vanilla all-false instance (forward/backward/left/right/jump/sneak/sprint).
                // Suppressed whenever a replay/clip is active (every mode incl. Legacy playclip) so the
                // player never walks server-side while spectating a rewind/clip. Mouse-look (rotation)
                // is untouched so the user can still look around in modes with no freecam (Legacy, /ius replay).
                input?.keyPresses = Input.EMPTY
            }
        } catch (_: Throwable) {
            // fail-open: leave the player's input as-is (worst case = a one-tick walk, never a crash)
        }
    }
}