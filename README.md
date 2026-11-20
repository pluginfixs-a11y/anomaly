# Anomaly Fields

Server-side Fabric mod for Minecraft **26.2**. Anomalous zones ("columns") start past 2000 blocks from world
spawn and get more frequent and stronger the further you go. Vanilla clients need nothing installed: every cue
is a vanilla one — particles, sounds, potion effects, an action-bar line.

It pairs with MobScaler (mob stats grow with distance) but does not depend on it.

## Anomaly types

Each anomaly is a **vertical column**: radius is measured in X/Z, and it is active at any height. That keeps
placement cheap (no surface lookup) and means a column in a cave is just as real as one in the open.

| id | name | what it does |
|---|---|---|
| `gravity` | Тяжесть | pulls you toward the centre, slowness; past 60 % dose also mining fatigue |
| `vortex` | Вихрь | pushes you away from the centre, nausea; past 60 % dose lifts you up (at most 3 s in a row) |
| `frost` | Мороз | slowness + freeze counter (screen frost); past 80 % dose deals freeze damage |
| `shadow` | Мрак | darkness (dim screen, heartbeat); past 70 % dose blindness |
| `static` | Разряд | visual lightning bolts striking inside the column; a close hit hurts |

Velocity pushes (`gravity`, `vortex`) are sent to the client as a `ClientboundSetEntityMotionPacket`, the same
way vanilla explosions do it: a bare server-side `push` never reaches the player's own client, because the
client owns its position and the entity tracker skips the player entity for itself.

## Dose

Standing inside accumulates **dose** 0..1 at `dose_gain_per_second` × the anomaly's strength (0.6–1.0, grows
with distance): 20–33 seconds to full. Outside, dose decays at `dose_decay_per_second`, and while it stays above
`lingering_floor` the anomaly's primary effect keeps hanging on you. That is the "the longer you stand, the
longer it sticks" rule: a full dose takes 50 seconds to fade, so it still hurts well after you left. Walking
from one anomaly type into another does not carry the old dose: it is clamped to 30 % on the switch, otherwise a
step from a full "gravity" into "frost" would hit at full strength with no ramp-up.

Effect amplifier: 0 below 45 % dose, I from 45 %, II from 80 %. The action bar shows `Тяжесть · 62 %` while you
are in an anomaly or still carrying its tail.

## Placement

No files, no `SavedData`: whether a chunk has an anomaly, where inside it, which type, its radius and its
strength are all derived from `splitmix64(worldSeed, chunkX, chunkZ)`. The same world always has the same
anomalies; a new world gets different ones.

Density is a list of `[distance, chance per chunk]` stages — 0.008 at 3000 blocks (one anomaly per ~125 chunks,
i.e. roughly every 180 blocks), 0.050 at 20000 (one per ~20 chunks, roughly every 70 blocks). Past `stack_from`
(10000) a chunk can hold two anomalies (25 % of chunks) or three (7 %, rolled independently).

## Feedback (and what is not possible)

The mod is server-only, so there is **no HUD, no custom visuals, no per-player fog**. Everything the player
learns comes from:

- particles and an occasional sound at the column within 48 blocks — you see an anomaly before you enter it
  (particles are sent with `overrideLimiter`, otherwise the server cuts them at 32 blocks);
- a chime and `Аномалия: Мрак` on entry, `Мрак · 62 %` in the action bar while inside;
- the vanilla effect icons in the inventory.

One idea that does not work without a client mod: a true "elytra off" zone. The server cannot cleanly stop
gliding (the client predicts flight, so forcing it off just jitters). `vortex` throws you upward instead, which
reaches flyers too.

## Command

`/anomaly` (gamemasters): config summary. `/anomaly dose` — your dose and amplifier. `/anomaly near` — up to six
anomalies within 48 blocks with type, radius and distance. Meant for checking the mod without flying 20k
yourself.

## Config

`config/anomaly.json`, generated on first start; editing needs a server restart. Keys: `enabled`,
`overworld_only`, `safe_radius`, `density_stages`, `radius_min`, `radius_max`, `stack_from`,
`stack_second_chance`, `stack_third_chance`, `dose_gain_per_second`, `dose_decay_per_second`,
`lingering_floor`, `view_range`, `sound_period`, `tick_period`, `disabled_types`.

A broken file is copied to `anomaly.json.bak` before the defaults replace it. A `null` or single-stage
`density_stages` means "no anomalies" rather than a crash.

## Install

Fabric Loader ≥ 0.20.1 + Fabric API on the server, jar into `mods/`. Server-side only: in a single-player world
the mod is not loaded at all (`environment: "server"`).

## Self-test

`-Danomaly.selftest=1` runs 34 checks on the first tick: density curve (0 below `safe_radius`, monotone, in
0..1), placement determinism (same chunk twice → same anomalies), radius/strength bounds, no anomalies near the
centre, far chunks have at least 3× more than mid ones, stack rules, dose arithmetic, amplifier thresholds, the
type-switch clamp and the inside-ticks counter, config edge cases (`null`/single/falling stages, `null` disabled
list), plus live world calls that do not need a connection: player push, freeze counter, particles, sound,
lightning spawn. Each group runs in its own try/catch, so one crash does not hide the rest.

Not covered by the test: `addEffect`, `hurt` and the action bar — a bare `ServerPlayer` has no `connection` and
they throw. Those paths need a real client.

## Build

```
./gradlew build
```

## License

CC0-1.0.
