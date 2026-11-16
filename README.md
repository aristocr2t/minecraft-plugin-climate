# Climate

Seasons for the world and for the player, in one plugin (Paper 26.3, JDK 25).

**World** (`/climate scan|winter|...`, permission `seasons.admin`): a SQLite map of the original
biomes plus seasonal passes. Winter lays snow (up to 7 layers), freezes shore
water edge-first through frosted ice stages and buries grass; spring restores
the original biomes/block-by-block and re-turfs the scars; autumn shortens the
grass. The far pass keeps unattended chunks weathering at exactly the vanilla
rate and seasons chunks generated after the season was applied.

**Player** (`/climate status|season|set|...`, permission `thermo.admin`): body temperature with
clothing (leather/netherite are warm suits), activity, illness, a thermometer
item; day rest (lie by day, wake in the morning) and an event-driven night
skip; a seasonal weather director (winter snows more, summer thunderstorms);
the winter blizzard — wind-blown snowflakes around players in snowy biomes.

The season clock lives in the thermo core; changing it applies the season in
the world too, so both halves always agree.

## Build

    ./build.sh

Docker builds `climate-1.0.0.jar` (`lib/*.jar` is the compile classpath; the
SQLite driver is unpacked into the jar).

## Configuration and localization

- `config.yml` — world passes (`far-weather`, `thaw`, `grass`...), player
  model (`temperature`, `clothing`, `illness`, `rest`...), `weather-director`,
  `blizzard`, `wind`.
- `lang/en.yml`, `lang/ru.yml` — player- and admin-facing strings
  (`seasons.*` for world messages, `thermo.*` for the player). Drop a custom
  `<code>.yml` into `plugins/Climate/lang/` and set `language` to use it.
