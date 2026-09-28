# Horde Events

On a random timer a horde event fires: one configured mob type is picked, and for a randomised
duration every natural spawn of that type arrives with extra mobs of the same type alongside it. The
world floods with zombies for a few minutes, then goes quiet. There is no UI and no announcement -
players just notice the swarm.

One event runs at a time. When it ends, the gap to the next is re-rolled.

## It rides natural spawns

The extras are not spawned on a timer or around players - they hang off the vanilla spawner. Fabric
API has no spawn-reason event, so a mixin on `Mob.finalizeSpawn` filters to `NATURAL` and hands each
spawn to the manager, which decides how many extras to add and where.

That is what makes the flood feel like the world doing it rather than a plugin doing it: extras
appear wherever mobs were already going to appear, under the same light and cap rules.

The extras themselves spawn with reason `EVENT`, not `NATURAL`, so they never re-enter the same
handler. Without that the first spawn would trigger extras, which would trigger extras, until the
server fell over.

## Server-side only - players install nothing

Vanilla mobs, a vanilla command, no new content. `/horde` and its tab-completion are synced to
clients by the server automatically.

## Requirements

Drop these into your **server's** `mods/` folder:

| Mod | Version |
| --- | --- |
| `horde-events-fabric-0.1.0.jar` | this mod |
| [Fabric API](https://modrinth.com/mod/fabric-api) | `0.155.2+26.2` (or compatible) |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | `1.13.12+kotlin.2.4.0` (or compatible) |

Minecraft **26.2**, Fabric Loader **0.19.3+**, **Java 25**.

## Install

1. Put the three jars in `<server>/mods/`.
2. Start the server once. A default config is written to `<server>/config/horde-events.json`.
3. Edit it and `/horde reload`, or tune it live with the commands below.

## Config (`config/horde-events.json`)

JSON, via the Gson that Minecraft already ships. One `defaults` block holds every per-horde setting;
each mob entry overrides only what it wants and inherits the rest. `eventInterval` governs when
*any* horde fires, so it sits outside `defaults`.

```json
{
  "eventInterval": { "min": 600, "max": 1500 },
  "defaults": {
    "duration":     { "min": 120, "max": 300 },
    "rate":         { "min": 8,   "max": 15 },
    "spread":       { "min": 2,   "max": 6 },
    "spreadRadius": { "min": 0.0, "max": 1.5 }
  },
  "mobs": {
    "ZOMBIE": {}
  }
}
```

| Setting | Meaning |
| --- | --- |
| `eventInterval` | seconds between events, re-rolled after each one |
| `defaults.duration` | seconds an event lasts |
| `defaults.rate` | extra mobs per natural spawn |
| `defaults.spread` | seconds a batch trickles in; `0` spawns it all at once |
| `defaults.spreadRadius` | blocks each extra fans out from the trigger, decimals fine |
| `mobs` | eligible types; `{}` inherits everything, or override any subset |

Keys under `mobs` are entity ids - `ZOMBIE` and `minecraft:zombie` both work - and must be living,
spawnable mobs. Unknown keys are skipped with a warning on load rather than failing the file.

```json
"mobs": {
  "ZOMBIE": {},
  "SKELETON": {
    "rate":         { "min": 6,   "max": 12 },
    "spreadRadius": { "min": 2.0, "max": 6.0 }
  }
}
```

## Commands (`/horde`, op level 2)

```
/horde start [type] [seconds]   fire an event now; random type and duration if omitted
/horde force <type> [duration] [rateMin] [rateMax] [spreadMin] [spreadMax]
                                horde any living type, configured or not; omitted
                                args fall back to that type's resolved settings
/horde stop                     end the active event
/horde status                   active type, time left and settings, or time to next
/horde rate <type> <min> <max>  per-type extra-per-spawn override     (persists)
/horde default <duration|rate|spread|radius> <min> <max>
                                set a global default                  (persists)
/horde interval <min> <max>     gap between events, in seconds        (persists)
/horde list                     defaults plus per-type overrides
/horde reload                   re-read config/horde-events.json
/horde help
```

Setters write straight back to the config file.

### Examples

```
# A zombie horde now, using its configured settings:
/horde force zombie

# 60 seconds, heavier rate, spread and radius left alone:
/horde force zombie 60 25 40

# Same, plus a longer trickle window:
/horde force zombie 60 25 40 8 14

# A type that isn't in the config at all, using the global defaults:
/horde force husk

# Make every horde swarm harder and fan out more:
/horde default rate 12 24
/horde default radius 3 8

# Give skeletons their own rate, then run one:
/horde rate skeleton 6 12
/horde start skeleton 90

# Automatic events every 5 to 10 minutes:
/horde interval 300 600
```

## Limits

Per-type overrides for duration, spread and radius are config-only - the commands cover the global
defaults and per-type rate. Because extras ride natural spawns, a horde in a fully lit or fully
spawn-proofed area produces nothing; there is no floor.
