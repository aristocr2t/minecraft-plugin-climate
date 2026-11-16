# Climate — agent guide

Climate is one Paper 26.3 plugin: a seasonal world (biomes, snow, ice, far weather
passes) and a player body (temperature, clothing, illness, day rest, night sleep).
Java 25, single package `ru.geekideas.mc.climate`, one commit on `main`.

## Commands

- `./build.sh` — build `climate-1.0.0.jar` in Docker (`eclipse-temurin:25-jdk`).
- `./format.sh` — google-java-format 1.26.0 under JDK 21 (it crashes on JDK 25).
- Verify after every change: run both, then confirm the jar contains the classes
  (`unzip -l climate-1.0.0.jar | grep ClimatePlugin`).

## Architecture

- `ClimatePlugin` — the JavaPlugin entry point; owns one `/climate` command whose
  dispatcher routes subcommands to the two cores. No aliases.
- `Terrain` — world core: biome map (`Store`, SQLite), seasonal passes
  (`Scanner`, `Applier`, `FarWeather`, base `Pass`), config, lang.
- `Body` — player core: temperature model, clothing, illness, day rest, night
  skip, weather director, blizzard.
- `Env` — environment probe (biome, weather, shelter, heat blocks).
- `Lang` — one i18n loader; call sites use plain keys.

Season clock rule: `Body` owns `season.yml`; `setSeason` applies the season in
the world through the `/climate` dispatcher and refuses while `Terrain.busy()`.
Never move the clock when the world cannot follow.

## Code conventions

Follow `author-code-style` (adapted to Java; established code wins over the
generic rule where they differ):

- 2-space indent via `./format.sh`; run it after editing, never hand-format.
- `catch (ExceptionType err)` — caught values are always named `err`.
- Layout is semantic grouping (all rules with examples):
  - Fields and imports: one tight group, no blank lines between members.
  - Statements of one thought stay adjacent; different thoughts part with a
    blank line (every config setting on its own; flag vs wind vector vs
    position vs derived count).
  - Declarations: same type and a common name prefix stay together
    (`int radius` + `int particles`, `double speed` + `thunderFactor`,
    `String biomeKey` + `biomeName`, `double angle` + `driftX` + `driftZ`,
    related locals like nx/nz, dx/dz/y); a type change parts the lines
    (int pair || double pair || long now; `long until` || `boolean sick`).
  - Assignments with a common RHS head stay together
    (thermometerKey/bodyKey/illnessKey = `new NamespacedKey(...)`);
    constructor field initializers stay together.
  - Calls join only within one form, one receiver, one thought: both bare
    functions (`loadStates()` + `updateThermometerRecipe()`), both instance
    calls on the SAME receiver (`bar.set...`, `yml.set...`,
    `plugin.reloadConfig()` + `worldsReload()`), or both static
    (`Lang.load()` twice). Any form or receiver change parts the lines
    (instance to static, receiver to bare, `bar.setVisible` to
    `player.sendActionBar`); a cleanup sequence (`*.remove(id)` x5) parts
    from a following different call (`clearDayRest(id)`); related call pairs
    (`clearDayRest` + `cancelMorningWake`) stay together.
  - A declaration and a call are different actions — blank line between, in
    either order. A blank line before `return` after a statement.
  - Braces on EVERY body; single-statement ifs become multiline blocks, and
    neighbor if-blocks part with a blank line.
  - Blank lines around multiline expressions: after a statement that starts
    on one line and ends on another, and before a braced `if/for/while` that
    follows a statement; blank lines between switch case arms. Never pad
    right after an opening `{`.
  - Hoist repeated getters into a local: one `player.getUniqueId()` per
    method, not five. Pattern variables keep full names (`player`, not `p`).
- Naming: integer counts end in `Count` (`chunkCount`, `maxChunkCount`),
  maps end in `Map` (`goalMap`, `bodyMap`), arrays and lists stay plural,
  classes are singular (`Terrain`, `Body`, `Season` enum). Booleans read as
  state: `frostedIce`, `closing`.
- Find/get contract: `findOverworld()` / `findSeasonName()` return `null` or `""`
  on absence; predicates are subject-first (`chunkExists`, `hasPending`).
- Guard clauses for early exits; braces on EVERY body, including
  single-statement ifs (multiline, blank line between neighbor ifs);
  comments explain the hidden constraint or the why, in English, one line
  when possible.
- New config keys: read with a default in code, add the key with a comment to
  `config.yml`, never break old configs.

## Performance rules (hot paths)

The far weather pass touches thousands of blocks per tick; keep it cheap.

- No repeated lookups of the same value inside loops or per attempt: hoist
  `getMinHeight()`, `getType()`, `hasStorm()` into locals or pass fields.
- Big-O: chunk membership checks use the sorted `long[] knownChunkKeys` with
  `Arrays.binarySearch` (log n), never a scan; `Store.chunkKeys` returns the
  sorted array without building a map.
- Per-chunk work obeys the tick budget: `FarWeather.process` stops attempts at
  its time share (`farMillisPerTick / farChunksPerTick`).
- Bukkit main thread only for world and store access; Netty callbacks hand off
  via `Bukkit.getScheduler().runTask`.
- SQLite work stays short; a failure aborts the pass (`stop()`) instead of
  leaving holes.

## Seasonal design facts (do not break)

- Snow accumulates only in `far-weather.snow-biomes` during storms, up to
  `max-snow-layers`; the per-chunk attempt rate mirrors vanilla (1 attempt per
  16 ticks of pass time).
- Melting is gradual and symmetric (one layer per attempt, staged ice:
  ice -> frosted -> snow-on-water -> water). Reset `Snowable.snowy` when the
  last snow layer above grass is removed, and repair bare gray grass on sight.
- Natively snowy biomes keep snow through autumn/winter/spring; only summer
  maps them warm (`summer-map`) so snow melts and rain can fall; `autumn-map`
  and `winter-map` return them to snow. Dry biomes (savanna, desert) get rain
  in winter via `winter-map: plains`.
- Block light softens snow edges: gradient `snowAllowedByLight`, not a hard
  cutoff; paths (`DIRT_PATH`) never get snow.
