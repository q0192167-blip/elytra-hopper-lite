# Elytra Hopper Lite - Patch Notes

## Current version: v8 (landing level + onGround + cooldown throttle, fixes "jumps once then deadlocks")

File: `8_elytra-hopper-lite-PATCHED.jar` (numeric prefix = version number)

> Please download the jar with the **largest numeric prefix**, i.e. `8_elytra-hopper-lite-PATCHED.jar`. Older versions (1-7) all have known defects and are no longer recommended.

## What v8 fixes (important)

Although v7 fixed the crash, it introduced "jumps once then stops / becomes manual again" - the root cause was an **awaitingRecast latch deadlock**:

- The moment of landing triggers a recast -> sets the latch `awaitingRecast=true`, and `previousFallFlying` is set to `false`
- If that takeoff is not acknowledged (server flips `fallFlying` back to `false`), the latch condition "unlock only when `fallFlying=true`" is never met -> **permanently locked**
- The only way out is releasing jump to re-arm via reset -> shows up as "manual, jumps once"

v8 completely removes the edge + latch, replacing it with the simplest **landing level detection**:

```
If  not flying(!fallFlying) AND on ground(onGround) AND cooldown over(cooldown==0)
    -> recastFlight() recast, apply 20-tick cooldown
Jump released / precondition failed -> reset and stop immediately
```

- **No more deadlock**: as long as jump is held and the player is on the ground, it retries as soon as the cooldown ends; no edge/latch dependency
- **No flapping**: `onGround` prevents recasting when flipped back in the air; the 20-tick cooldown caps it at once per second
- **Stop on release**: releasing jump resets immediately

All 4 state-machine simulator scenarios pass (normal loop / stable retry when takeoff rejected / 0 recasts when flipped back in air / stop on release), and all bytecode owners are `LocalPlayer` (no v6 NoSuchMethodError risk).

## What this mod does

The core function of the original Elytra Hopper: while gliding in the air, hold jump at the moment of landing and the mod automatically re-opens your wings for takeoff, enabling continuous hopping travel. This patch adds throttling and detection on top, to avoid anti-cheat flagging from switching state too quickly.

## What v6 fixed (important)

v5 had a fatal bug: landing detection used `onGround() || block below is non-air`.

The problem: **while gliding in the air the block below is the ground, so "block below non-air" is always true**, making `grounded` permanently true in the air. So every time the server flipped the flying state back to movement, the mod misjudged it as "landed", immediately re-opened wings -> flipped back again -> re-opened again, appearing as frantic open/close flapping in the air while holding jump.

v6 fixes:
- **Landing criterion uses only `onGround()`** (real ground contact), completely removing the block-below check (that one is always true in the air, unusable)
- Restores the original **landing edge detection** `!fallFlying && previousFallFlying`: only considers a recast at the exact instant "flying last frame, not flying this frame"
- Restores the **awaitingRecast latch**: after one recast it locks, unlocking only once takeoff is confirmed (`fallFlying=true`); if flipped back to movement by the server it stays locked and never retries - this is the key to eliminating repeated wing-opening
- Keeps the **20-tick (about 1 second) cooldown** and **hold-jump master switch** (releasing jump resets and stops immediately)

## Per-tick logic

```
Precondition failed / jump released -> reset all state and stop
Cooldown > 0                        -> decrement
fallFlying = isFallFlying()
onGround   = onGround()          // only landing criterion

if (latched):
    takeoff confirmed(fallFlying=true) -> unlock
    flipped back to movement           -> keep latch, never retry
else if (!fallFlying AND was flying last frame AND onGround AND cooldown over):
    recast once + latch + 20-tick cooldown

While flying, record momentum (preserve original inertia conservation)
```

## Static verification

Ran 5 edge-case scenarios with the state-machine simulator, all passing:

| Scenario | Result |
|---|---|
| Normal hopping loop | ✅ recast only on landing, cooldown between each |
| **Repeatedly flipped back in air (v5 flapping scenario)** | ✅ **0 recasts, no more open/close flapping** |
| Recast rejected after real landing | ✅ latch engaged, no frantic retry |
| Short flight + cooldown throttle | ✅ no repeat during cooldown |
| Release and re-press recovery | ✅ stop on release, re-arm on re-press |

Bytecode verification: class major 69, StackMapTable intact, the flapping-causing `getBlockState/isAir` APIs completely removed, method reference signatures matched one by one against MC 26.1.2 official mappings (ruling out runtime NoSuchMethodError).

## Version history

| Version | Logic | Problem |
|---|---|---|
| v1 | landing edge + cooldown skip | edge lost, jumps once then stops |
| v2 | delayed-recast state machine | 2-tick frantic retry |
| v3 | hold level detection | flapping when state flipped back |
| v4 | edge + latch (no onGround) | flip-back in air misjudged as landing |
| v5 | state + onGround + block below | block below always true in air -> airborne flapping |
| v6 | edge + onGround + latch | airborne flapping fixed, but wrong owner caused crash |
| v7 | v6 + owner changed to LocalPlayer | crash fixed, but latch deadlock -> jumps once |
| **v8** | **landing level + onGround + 20t cooldown, no latch** | **current version** |

## Usage

Put the jar in the mods folder. While gliding in the air, hold jump at the moment of landing to auto-recast; release jump to stop.

Note: this is a client-side mod, runs purely on the client, no server install needed. However, the server's anti-cheat has final authority over "landing re-open elytra" - if the server actively rejects takeoff, v8 will simply retry on each cooldown instead of flapping; release and re-press to re-arm.

## v7 hotfix (crash fix)

v6 crashed immediately on entering the game, stack trace:

```
java.lang.NoSuchMethodError: 'boolean net.minecraft.world.entity.Entity.isFallFlying()'
    at com.santr.elytrahopper.ElytraHopperClient.recastIfStoppedFallFlying
```

**Root cause**: the v5/v6 ASM patch hard-coded the call owner of `isFallFlying()`, `onGround()`, `getDeltaMovement()` as `Entity` (`net/minecraft/world/entity/Entity`). But the real declaring class of these methods is not Entity (`isFallFlying` is on `LivingEntity`), and in the original bytecode all calls use `LocalPlayer` as owner. Sinytra Connector builds its mapping table from "reference combinations that appeared in the original jar"; the combination `Entity.isFallFlying` never appeared in the original, so it was not remapped -> method does not exist at runtime -> crash.

**Fix**: v7 changed all three owners back to `LocalPlayer`, byte-for-byte identical to the original bytecode. Cross-check: relative to the original, the patched class adds only two references - its own `hopCooldown` field (safe) and `LocalPlayer.onGround` (LocalPlayer's inheritance chain includes Entity.onGround, valid).

**Lesson**: when ASM-patching an already-compiled jar, every reference to MC classes must match the original bytecode's owner/name/desc exactly; do not change the owner based on official mappings - Connector/NeoForge mapping tables only cover reference combinations that appeared in the original.
