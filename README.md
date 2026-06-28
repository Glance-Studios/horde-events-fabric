# Horde Events (Fabric)

A small **server-side** Fabric mod for Minecraft **1.21.11**. On a random timer a **horde event**
fires: it picks a configured mob type and runs for a randomized duration. While it runs, every
NATURAL vanilla spawn of that type is accompanied by a randomized number of **extra** mobs of the
same type, so that mob floods the world for a while. There is no UI - players just notice the swarm.

Only one event runs at a time; when it ends, the gap to the next is re-rolled.

This is a Fabric port of the Paper `HordeEvents` plugin, with identical behavior.

## Server-side only - players install nothing

This mod runs **only on the server**. It uses vanilla mobs and a vanilla command and registers no
new content, so clients connect with a normal (or vanilla) client and need none of this installed.
The `/horde` command and its tab-completion are synced to clients automatically by the server.

## Requirements

Drop these into your **server's** `mods/` folder:

| Mod | Version |
| --- | --- |
| `horde-events-fabric-0.1.0.jar` | this mod |
| [Fabric API](https://modrinth.com/mod/fabric-api) | `0.141.4+1.21.11` (or compatible) |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | `1.13.12+kotlin.2.4.0` (or compatible) |

- Minecraft **1.21.11**, Fabric Loader **0.19.3+**, **Java 21**.
- Fabric Language Kotlin supplies the Kotlin runtime - it's required on the server only.

## Install

1. Put the three jars above in `<server>/mods/`.
2. Start the server once. The mod writes a default config to `<server>/config/horde-events.json`.
3. Edit the config (below) and `/horde reload`, or tune it live with `/horde` commands.

## Config (`config/horde-events.json`)

JSON (this port uses the Gson that Minecraft already ships - no extra dependency). One global
`defaults` block holds every per-horde setting; each mob entry overrides only the bits it wants and
inherits the rest. `eventInterval` (when *any* horde fires) is system-wide and lives outside
`defaults`.

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
| `eventInterval` | seconds between events (system-wide; re-rolled after each event) |
| `defaults.duration` | seconds an event lasts |
| `defaults.rate` | extra mobs per natural spawn |
| `defaults.spread` | seconds a triggered batch trickles in (`0` = spawn instantly) |
| `defaults.spreadRadius` | blocks each extra mob fans out from the trigger (decimals ok) |
| `mobs` | eligible types; `{}` inherits all defaults, or override any subset |

Keys under `mobs` are entity ids - `ZOMBIE` or `minecraft:zombie` both work - and must be
living/spawnable mobs; unknown keys are skipped with a warning on load. Per-type override example:

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
/horde start [type] [seconds]   fire an event now (random type/duration if omitted; type must be configured)
/horde force <type> [duration] [rateMin] [rateMax] [spreadMin] [spreadMax]
                                horde ANY living type now; omitted args use that type's
                                resolved settings (its overrides on the defaults)
/horde stop                     end the active event
/horde status                   active type + time left + settings, or time to next
/horde rate <type> <min> <max>  per-type extra-per-spawn override     (persists)
/horde default <duration|rate|spread|radius> <min> <max>
                                set a global default                  (persists)
/horde interval <min> <max>     set gap between events in seconds     (persists)
/horde list                     show defaults + per-type overrides
/horde reload                   reload config/horde-events.json
/horde help                     command reference
```

Setters write straight back to `horde-events.json`. Per-type overrides for duration/spread/radius
are config-only (edit `mobs.<TYPE>.<setting>`); commands cover global defaults + per-type rate.

### Examples

```
# Fire a zombie horde now using its configured settings (random duration):
/horde force zombie

# Forced 60s zombie horde with a heavier rate, leaving spread/radius at resolved values:
/horde force zombie 60 25 40

# Forced 60s zombie horde, heavy rate AND a longer trickle window (8-14s):
/horde force zombie 60 25 40 8 14

# Horde a type that isn't in config at all (uses the global defaults):
/horde force husk

# Make every horde swarm harder and fan out more, via the global defaults:
/horde default rate 12 24
/horde default radius 3 8

# Give skeletons their own per-type rate, then run one:
/horde rate skeleton 6 12
/horde start skeleton 90

# Tune the gap between automatic events to 5-10 minutes:
/horde interval 300 600

# Inspect everything:
/horde list
/horde status
```
