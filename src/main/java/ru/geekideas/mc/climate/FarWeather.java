package ru.geekideas.mc.climate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Snowable;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Player;

/**
 * Far weather, both directions. Accumulation runs only in chunks without a player nearby (the
 * vanilla algorithm handles those); melting runs in every chunk. Chunks that were already loaded by
 * other means (spawn, force-load, other plugins) are processed but never unloaded; chunks loaded by
 * this pass are unloaded after:
 *
 * <p>Winter / precipitation in a snow biome — accumulation, mimicking the vanilla chunk-tick
 * weather attempt (on average 1 column per 16 ticks per chunk). Accumulation skips chunks near
 * players (vanilla handles them); melting runs in every chunk, including player-adjacent ones —
 * vanilla never melts snow by biome: - snow only onto air above a block with a solid top, not where
 * block light >= 10; each successful attempt adds exactly one layer, same rate for every layer
 * (vanilla caps layers by the snowAccumulationHeight game rule, we use max-snow-layers); - ice only
 * from still water exposed to sky in darkness (block light < 10), starting from river banks and
 * sea/ocean shores and spreading inward as new edges appear.
 *
 * <p>Warm biome (spring/summer/autumn restore) — gradual melting at the same attempt rate: one snow
 * layer per successful attempt, ice turns into water, frosted ice disappears.
 *
 * <p>A chunk that exists on disk but is not in the biome map was generated after the season was
 * applied: it still has the original biomes and looks like spring. On its first visit the pass
 * records the originals, applies the season and dresses the surface in one go
 * (new-chunk-seasoning), so the chunk looks as if the season had run there all along.
 *
 * <p>A pass covers the whole generated world (region files) in a shuffled uniform order. Attempts
 * per chunk visit = attemptCount-per-tick × ticks in one full pass, capped, so the average rate
 * matches a chunk that had a player nearby all the time.
 */
final class FarWeather extends Pass {
  private static final BlockFace[] HORIZONTAL = {
    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
  };

  /**
   * Wind direction in degrees: a full circle every wind.cycle-days days plus a slow gust sway.
   * Stateless on purpose — derived from world time, and byte for byte the same formula the Thermo
   * plugin uses, so snow and blizzard particles agree.
   */
  static double windAngleDeg(World world, double cycleDays) {
    double day = world.getFullTime() / 24000.0;

    return (day / cycleDays) * 360.0 + 25.0 * Math.sin(day * 0.45) + 10.0 * Math.sin(day * 2.1);
  }

  private final List<long[]> queue = new ArrayList<>();
  private final int attemptsPerVisit;
  private final Random random = new Random();
  private final long[] known;
  private final int minHeight;
  private Season applied;
  private Map<String, String> goals = Map.of();
  private int index;
  private int weatherChanged;
  private int seasonedCount;
  private int notDirtRejectCount;
  private int coveredRejectCount;
  private int seededCount;
  private int wildSeedCount;

  FarWeather(Terrain plugin, World world, Store store) {
    super(plugin, world, store, plugin.farChunksPerTick, plugin.farMillisPerTick);

    Set<Long> existing = Scanner.existingChunks(Scanner.regionDir(world));

    // Chunks in the map are already seasonal. A chunk that exists on disk but is not
    // in the map was generated after the last scan or season pass, so it still has the
    // world's original biomes and has to catch up with the season on its first visit.
    // Sorted keys instead of a set: the pass lives for hours and holds millions of them.
    this.known = store.chunkKeys(worldName);
    this.minHeight = world.getMinHeight();

    refreshApplied();

    long salt = random.nextLong();

    for (long key : existing) {
      queue.add(new long[] {Long.hashCode(key ^ salt), key});
    }

    queue.sort(Comparator.comparingLong((long[] row) -> row[0]).thenComparingLong(row -> row[1]));

    long ticksPerPass =
        Math.max(1, (queue.size() + plugin.farChunksPerTick - 1) / plugin.farChunksPerTick);

    this.attemptsPerVisit =
        (int)
            Math.min(
                plugin.farMaxAttemptCount,
                Math.max(1, Math.round(plugin.farAttemptsPerTick * ticksPerPass)));

    plugin
        .getLogger()
        .info(
            "[weather] far pass: "
                + queue.size()
                + " chunks, "
                + attemptsPerVisit
                + " attemptCount per chunk visit, "
                + ticksPerPass
                + " ticks per pass, new chunks: "
                + (applied == null
                    ? "left as is"
                    : "seasoned "
                        + applied.state
                        + " ("
                        + plugin.newChunkColumnCount
                        + " columns)"));
  }

  /**
   * Reads the season applied in the world right now. Called for a chunk that is not in the cached
   * chunk list only, so a duplicate chunk list entry costs two key lookups; it keeps a pass that
   * was started before a season change from seasoning new chunks with the previous season.
   */
  private boolean refreshApplied() {
    String name = store.meta("season_applied") == null ? null : store.meta("season_name");

    Season now = name == null ? null : Season.parse(name);

    if (now == applied) {
      return now != null;
    }

    applied = now;

    goals = now == null ? Map.of() : plugin.goals(now);

    return now != null;
  }

  void reloadGoals() {
    if (applied != null) {
      goals = plugin.goals(applied);
    }
  }

  @Override
  String label() {
    return "weather";
  }

  /** Snow/ice/water changes only — cosmetic grass work must not keep the pass awake. */
  int weatherChanges() {
    return weatherChanged;
  }

  @Override
  boolean countsAsOperation() {
    return false; // background weather simulation, not a user-facing operation
  }

  @Override
  boolean hasPending() {
    return index < queue.size();
  }

  @Override
  void dispatchNext() {
    long[] entry = queue.get(index++);

    int chunkX = Store.chunkX(entry[1]);
    int chunkZ = Store.chunkZ(entry[1]);

    boolean unknown = Arrays.binarySearch(known, entry[1]) < 0;
    boolean playerNear = playersNear(chunkX, chunkZ);
    boolean wasLoaded = world.isChunkLoaded(chunkX, chunkZ);

    inFlightCount++;

    world
        .getChunkAtAsync(chunkX, chunkZ, false)
        .whenComplete(
            (chunk, error) -> {
              chunkCompleted();

              if (error != null) {
                plugin
                    .getLogger()
                    .warning("[" + label() + "] chunk " + chunkX + "," + chunkZ + ": " + error);

                chunkCount++;

                return;
              }

              if (chunk == null || isFinished()) {
                chunkCount++; // does not generate chunks that don't exist; a stopped pass stays off

                return;
              }

              // Before the ordinary attemptCount: a fresh chunk must already be seasonal,
              // otherwise melt() would eat the snow it is about to get. Nothing is seasoned
              // while a scan or season pass runs — the season is about to change under us.
              if (unknown
                  && !isFinished()
                  && plugin.newChunkSeasoning
                  && !plugin.busy()
                  && refreshApplied()) {
                try {
                  seasonChunk(chunk);
                } catch (RuntimeException err) {
                  plugin
                      .getLogger()
                      .severe("[weather] new chunk " + chunkX + "," + chunkZ + ": " + err);
                }
              }

              process(chunk, playerNear);

              // Unload only chunks we loaded ourselves; chunks kept loaded by the spawn,
              // force-load or other plugins stay loaded.
              if (!wasLoaded && !playersNear(chunkX, chunkZ)) {
                world.unloadChunkRequest(chunkX, chunkZ);
              }

              chunkCount++;
            });
  }

  private void process(Chunk chunk, boolean playerNear) {
    int baseX = chunk.getX() << 4;
    int baseZ = chunk.getZ() << 4;

    // the tick budget is shared by all chunks dispatched this tick: stop at our share
    long attemptDeadline =
        System.currentTimeMillis()
            + Math.max(2L, plugin.farMillisPerTick / plugin.farChunksPerTick);

    boolean summer = plugin.isSummer();

    for (int i = 0; i < attemptsPerVisit && System.currentTimeMillis() <= attemptDeadline; i++) {
      applyAttempt(baseX + random.nextInt(16), baseZ + random.nextInt(16), playerNear, summer);
    }

    if (chunkCount % 500 == 0) {
      plugin
          .getLogger()
          .info(
              "["
                  + label()
                  + "] "
                  + chunkCount
                  + " chunks, "
                  + changedCount
                  + " changed, seasoned "
                  + seasonedCount
                  + "; regrow not-dirt "
                  + notDirtRejectCount
                  + ", covered "
                  + coveredRejectCount
                  + ", seeded "
                  + seededCount
                  + ", wild "
                  + wildSeedCount);
    }
  }

  /**
   * Catching-up pass for a chunk generated after the season was applied: it still has the world's
   * original biomes and looks like spring. The originals are writtenCount to the map first (so
   * restore can undo the season later), then the season is applied and the surface is dressed at
   * once — no half-seasonal chunk, whatever the pass deadline is.
   */
  private void seasonChunk(Chunk chunk) {
    int chunkX = chunk.getX();
    int chunkZ = chunk.getZ();

    if (store.hasChunk(worldName, chunkX, chunkZ)) {
      return; // another pass won the race: that pass recorded the pristine originals
    }

    int baseX = chunkX << 4;
    int baseZ = chunkZ << 4;

    List<Store.Cell> cells = new ArrayList<>(plugin.cellsPerChunk());

    for (int quartY = plugin.minQuartY(); quartY <= plugin.maxQuartY(); quartY++) {
      int y = quartY << 2;

      for (int dz = 0; dz < 4; dz++) {
        for (int dx = 0; dx < 4; dx++) {
          Biome biome = world.getBiome(baseX + dx * 4, y, baseZ + dz * 4);

          cells.add(new Store.Cell(y >> 4, dx, quartY & 3, dz, biome.getKey().getKey()));
        }
      }
    }

    int mappedCount = 0;

    for (Store.Cell cell : cells) {
      if (goals.containsKey(cell.original())) {
        mappedCount++;
      }
    }

    int[] cellsByState = new int[Season.values().length];

    cellsByState[applied.ordinal()] = mappedCount;

    cellsByState[Season.RESTORE.ordinal()] = cells.size() - mappedCount;

    store.insertChunk(worldName, chunkX, chunkZ, cells);
    store.putChunkState(worldName, chunkX, chunkZ, applied.state, cellsByState, 0);
    // Committed together: a crash before the biomes change must not leave the map
    // saying "original" while the world is seasonal — restore would skip the chunk.
    store.commit();

    int writtenCount = 0;

    for (Store.Cell cell : cells) {
      String goal = goals.get(cell.original());

      if (goal == null) {
        continue; // "native" icy or unchangeable biome
      }

      Biome target = plugin.biome(goal);

      if (target == null) {
        continue;
      }

      int x = baseX + cell.cellX() * 4;
      int z = baseZ + cell.cellZ() * 4;
      int y = (cell.sectionY() << 4) + cell.cellY() * 4;

      if (world.getBiome(x, y, z).getKey().getKey().equals(goal)) {
        continue;
      }

      world.setBiome(x, y, z, target);
      writtenCount++;
    }

    if (writtenCount > 0) {
      world.refreshChunk(chunkX, chunkZ);
    }

    dress(chunk);

    store.putChunkState(worldName, chunkX, chunkZ, applied.state, cellsByState, writtenCount);
    changedCount += writtenCount;

    seasonedCount++;

    weatherChanged++; // a seasoned chunk is work: the pass must not fall asleep on it
  }

  /**
   * Ground of a fresh chunk: only the first new-chunk-columns random columns are dressed, the rest
   * catches up through the ordinary attemptCount. Snow and ice go only to snow biomes, grass
   * matters in winter (buried to dirt) and autumn (shortened).
   */
  private void dress(Chunk chunk) {
    if (applied != Season.WINTER && applied != Season.AUTUMN) {
      return; // summer and spring leave no trace on the ground
    }

    int baseX = chunk.getX() << 4;
    int baseZ = chunk.getZ() << 4;

    int[] columns = new int[256];

    for (int i = 0; i < columns.length; i++) {
      columns[i] = i;
    }

    int picked = Math.min(plugin.newChunkColumnCount, columns.length);

    for (int i = 0; i < picked; i++) {
      int other = i + random.nextInt(columns.length - i);
      int column = columns[other];

      columns[other] = columns[i];

      columns[i] = column;

      dressColumn(baseX + (column & 15), baseZ + (column >> 4));
    }
  }

  /** One column: snow of about the map-average depth, bank ice, brown grass, shortened grass. */
  private void dressColumn(int x, int z) {
    int y = world.getHighestBlockYAt(x, z);

    if (y <= minHeight) {
      return;
    }

    // The height map ignores plants, so this is the ground; the grass standing on it
    // is looked at separately (a plant keeps the column bare where it survives).
    Block block = world.getBlockAt(x, y, z);

    Material type = block.getType();

    if (type.isAir() || type == Material.BEDROCK) {
      return;
    }

    if (applied == Season.AUTUMN) {
      Block plant = block.getRelative(BlockFace.UP);

      if (plugin.grassAutumn
          && plant.getType() == Material.TALL_GRASS
          && random.nextDouble() < 0.5) {
        plant.setType(Material.SHORT_GRASS, false);

        changedCount++;
      }

      return;
    }

    // Winter: grass dies back under the snow the same way the thaw pass does it
    // (tall grass is two blocks), so the column ends up like the rest of the map.
    for (int dy = 1; dy <= 2; dy++) {
      Block plant = block.getRelative(0, dy, 0);

      Material plantType = plant.getType();

      if (plantType != Material.TALL_GRASS && plantType != Material.SHORT_GRASS) {
        break;
      }

      if (!plugin.grassWinter || random.nextDouble() >= plugin.grassWinterChance) {
        return; // a surviving plant keeps the column bare, as elsewhere
      }

      plant.setType(Material.AIR, false);
      changedCount++;
    }

    if (!plugin.farSnowBiomes.contains(block.getBiome().getKey().getKey())) {
      return; // not a snow biome even after the season change: nothing falls here
    }

    if (type == Material.GRASS_BLOCK && random.nextDouble() < plugin.buriedGrassShare()) {
      block.setType(Material.DIRT, false);

      changedCount++;
    }

    if (type == Material.WATER) {
      if (random.nextDouble() < 0.5) {
        freeze(block); // bank ice, the shape the running attemptCount grow later
      }

      return;
    }

    Block above = block.getRelative(BlockFace.UP);

    if (!above.getType().isAir()
        || !snowAllowedByLight(above, above.getLightFromBlocks())
        || !block.getType().isSolid()
        || type == Material.DIRT_PATH) {
      return; // same rules as a running snow attempt — paths stay clear
    }

    int depth =
        (int)
            Math.max(
                1,
                Math.min(
                    plugin.farMaxSnowLayers,
                    Math.round(
                        plugin.averageSnowDepth()
                            + random.nextGaussian() * plugin.newChunkSnowSpread)));

    above.setType(Material.SNOW, false);

    Snow snow = (Snow) above.getBlockData();

    snow.setLayers(depth);

    above.setBlockData(snow, false);

    plugin.snowPlaced(depth);
    changedCount++;
  }

  /** One vanilla weather attempt: a random column, check on its topmost block. */
  private void applyAttempt(int x, int z, boolean playerNear, boolean summer) {
    int y = world.getHighestBlockYAt(x, z);

    if (y <= minHeight) {
      return;
    }

    Block top = world.getBlockAt(x, y, z);

    for (int guard = 0; top.getType().isAir() && y > minHeight && guard < 24; guard++) {
      top = world.getBlockAt(x, --y, z);
    }

    Material type = top.getType();

    if (type.isAir() || type == Material.BEDROCK) {
      return;
    }

    String biome = top.getBiome().getKey().getKey();

    if (!plugin.farSnowBiomes.contains(biome)) {
      // Warm biome: last winter's snow and ice go away gradually,
      // dirt scars next to surviving grass re-turf over time
      // (deep snow kills grass blocks in vanilla — they turn to dirt),
      // and in summer the grass itself grows and spreads, uniformly far
      // from players the same way the snow browns in winter.
      melt(top);
      ungrayIfBare(top, top.getRelative(BlockFace.UP));
      regrow(top);
      if (summer) {
        grow(top);
      }

      return;
    }

    // Vanilla kills grass under snow starting at 2 layers, but only where random
    // ticks run (near players) — reproduce it uniformly across the whole map.
    // No early return: a bare grass column must still receive its first snow layer.
    if (type == Material.GRASS_BLOCK) {
      Block above = top.getRelative(BlockFace.UP);

      if (above.getBlockData() instanceof Snow snow && snow.getLayers() >= plugin.snowKillLayers) {
        boolean buried = random.nextDouble() < plugin.grassDieChance;

        if (buried) {
          top.setType(Material.DIRT, false);

          changedCount++;
        }

        plugin.grassUnderSnow(buried);
      }
    }

    boolean storm = world.hasStorm();

    if (plugin.frostedIce && type == Material.FROSTED_ICE) {
      // Winter random ticks are off, so vanilla never advances a crust: the storm
      // thickens it ourselves, in chunks near players too.
      if (storm && (!playerNear || plugin.frostedIceNearMelt) && random.nextDouble() < 0.5) {
        growFrosted(top);
      }

      return;
    }

    if (!storm || playerNear) {
      return; // snow falls and water freeze only during precipitation, away from players
    }

    if (type == Material.WATER) {
      freeze(top); // near players vanilla freezes the water itself
    } else {
      snow(top, playerNear);
    }
  }

  /** Melting in warm biomes: one snow layer per attempt on the column top... */
  private void melt(Block top) {
    if (isMeltable(top.getType())) {
      meltOne(top);

      return;
    }

    // ...and on snow the column-top check cannot see: a layer resting on the
    // canopy above, or a blanket on the ground under the leaves.
    for (int dy = 1; dy <= 3; dy++) {
      Block above = top.getRelative(0, dy, 0);

      Material aboveType = above.getType();

      if (isMeltable(aboveType)) {
        meltOne(above);

        return;
      }

      if (!aboveType.isAir()) {
        break;
      }
    }

    Block cursor = top;

    for (int i = 0; i < 16; i++) {
      Block below = cursor.getRelative(0, -1, 0);

      Material type = below.getType();

      if (isMeltable(type)) {
        meltOne(below);

        return;
      }

      if (type.isAir() || type.name().contains("LEAVES")) {
        cursor = below;

        continue;
      }

      break;
    }
  }

  /** Summer growth: short grass grows tall, tall grass spreads short grass to neighbours. */
  private void grow(Block ground) {
    Block plant = ground.getRelative(BlockFace.UP);

    Material type = plant.getType();

    if (type == Material.SHORT_GRASS) {
      if (random.nextDouble() < plugin.grassGrowChance) {
        plant.setType(Material.TALL_GRASS, false);

        changedCount++;
      }
    } else if (type == Material.TALL_GRASS) {
      if (random.nextDouble() < plugin.grassSpreadChance) {
        BlockFace face = HORIZONTAL[random.nextInt(HORIZONTAL.length)];

        Block neighbor = ground.getRelative(face);
        Block neighborAbove = neighbor.getRelative(BlockFace.UP);

        Material neighborType = neighbor.getType();

        if ((neighborType == Material.GRASS_BLOCK || neighborType == Material.DIRT)
            && neighborAbove.getType().isAir()) {
          neighborAbove.setType(Material.SHORT_GRASS, false);

          changedCount++;
        }
      }
    }
  }

  /**
   * A grass block keeps its gray snowy look (snowy=true) after the snow above is gone — removing
   * the layer directly never resets the block. Fix every bare gray grass column the pass meets.
   */
  private void ungrayIfBare(Block ground, Block above) {
    if (!above.getType().isAir()
        || !(ground.getBlockData() instanceof Snowable snowy)
        || !snowy.isSnowy()) {
      return;
    }

    snowy.setSnowy(false);

    ground.setBlockData(snowy, false);

    changedCount++;

    weatherChanged++;
  }

  /**
   * Vanilla-style grass spread: dirt with air/plant above next to a grass block re-turfs at full
   * chance. With no living neighbor the ground is not dead forever: wind-blown seeds land anywhere
   * at the wild chance, start a new green island and the normal spread takes it from there.
   */
  private void regrow(Block top) {
    if (top.getType() != Material.DIRT) {
      notDirtRejectCount++;

      return;
    }

    Block above = top.getRelative(BlockFace.UP);

    Material aboveType = above.getType();

    if (!aboveType.isAir()
        && aboveType != Material.SHORT_GRASS
        && aboveType != Material.TALL_GRASS) {
      coveredRejectCount++;

      return;
    }

    for (BlockFace face : HORIZONTAL) {
      Block neighbor = top.getRelative(face);
      Block neighborAbove = neighbor.getRelative(BlockFace.UP);

      Material neighborType = neighbor.getType();
      Material neighborAboveType = neighborAbove.getType();

      // grass seeds: a live grass block or a grass plant rooted next to us
      if (neighborType == Material.GRASS_BLOCK
          || neighborAboveType == Material.SHORT_GRASS
          || neighborAboveType == Material.TALL_GRASS) {
        if (random.nextDouble() < plugin.grassRegrowChance) {
          top.setType(Material.GRASS_BLOCK, false);

          changedCount++;

          weatherChanged++; // re-turfing is real work: it keeps the pass awake
          seededCount++;
        }

        return;
      }
    }

    if (random.nextDouble() < plugin.grassRegrowWildChance) {
      top.setType(Material.GRASS_BLOCK, false);

      changedCount++;

      weatherChanged++;

      wildSeedCount++;
    }
  }

  private static boolean isMeltable(Material type) {
    return type == Material.SNOW
        || type == Material.SNOW_BLOCK
        || type == Material.ICE
        || type == Material.FROSTED_ICE;
  }

  private void meltOne(Block block) {
    Material type = block.getType();

    if (type == Material.SNOW) {
      plugin.snowMelted();

      Snow snow = (Snow) block.getBlockData();

      if (snow.getLayers() > 1) {
        snow.setLayers(snow.getLayers() - 1);

        block.setBlockData(snow, false);
      } else {
        block.setType(Material.AIR, false);

        ungrayIfBare(block.getRelative(BlockFace.DOWN), block);
      }
    } else if (type == Material.SNOW_BLOCK) {
      block.setType(Material.AIR, false);
    } else if (type == Material.FROSTED_ICE) {
      if (plugin.frostedIce) {
        meltFrosted(block); // thins age by age, water only after the last one
      } else {
        block.setType(Material.AIR, false); // staging off: gone in one step
      }
    } else if (type == Material.ICE) {
      // Staging: ice goes back to an old crust first, so nothing vanishes in one step.
      BlockData crust = plugin.frostedIce ? frostedCrust(3) : null;

      if (crust != null) {
        block.setBlockData(crust, false);
      } else {
        block.setType(Material.WATER, false);
      }
    }

    changedCount++;

    weatherChanged++;
  }

  /**
   * Vanilla refuses snow at block light >= 10, which draws a sharp snow edge around every torch. We
   * soften it into a probability gradient: the closer to a heat source, the rarer a layer — the
   * edge dissolves into a thin cover.
   */
  private boolean snowAllowedByLight(Block above, int light) {
    if (light >= 11) {
      return false; // truly lit: nothing accumulates
    }

    double factor = light <= 4 ? 1.0 : (11.0 - light) / 7.0;

    return random.nextDouble() < factor;
  }

  /** Snow: add a layer onto an existing layer, or place one onto air above a solid top. */
  private void snow(Block ground, boolean playerNear) {
    Block above = ground.getRelative(BlockFace.UP);

    int light = above.getLightFromBlocks();

    if (!snowAllowedByLight(above, light)) {
      return; // heat-scaled probability, softer than the vanilla refusal
    }

    BlockData aboveData = above.getBlockData();

    if (aboveData instanceof Snow snow) {
      if (snow.getLayers() < plugin.farMaxSnowLayers) {
        snow.setLayers(snow.getLayers() + 1);

        above.setBlockData(snow, false);

        plugin.snowLanded(false);

        changedCount++;
      }

      return;
    }

    Material groundType = ground.getType();

    if (!above.getType().isAir() || !groundType.isSolid() || groundType == Material.DIRT_PATH) {
      return; // covered, no support — or a trodden path, which stays clear
    }

    if (playerNear && light < 5) {
      return; // vanilla lays snow here itself at the same rate — no double work
    }

    above.setType(Material.SNOW, false);

    plugin.snowLanded(true);

    changedCount++;

    weatherChanged++;
  }

  /** Ice: freeze still surface water in darkness, edge-first (river banks, shores). */
  private void freeze(Block water) {
    BlockData data = water.getBlockData();

    if (!(data instanceof Levelled levelled) || levelled.getLevel() != 0) {
      return; // only still water
    }

    if (!water.getRelative(BlockFace.UP).getType().isAir() || water.getLightFromBlocks() >= 10) {
      return; // must be exposed to sky and dark
    }

    boolean edge = false;

    for (BlockFace face : HORIZONTAL) {
      Material side = water.getRelative(face).getType();

      if (side != Material.WATER && side != Material.ICE && side != Material.FROSTED_ICE) {
        edge = true; // land: a bank of a riverbed or a sea/ocean shore

        break;
      }
    }

    if (!edge) {
      return; // open water freezes later, as the shore ice spreads
    }

    // Staging: a bank starts as a young crust that storms thicken and thaw eats
    // age by age, instead of a full ice block popping in and out.
    BlockData crust = plugin.frostedIce ? frostedCrust(random.nextInt(3)) : null;

    if (crust != null) {
      water.setBlockData(crust, false);
    } else {
      water.setType(Material.ICE, false);
    }

    changedCount++;

    weatherChanged++;
  }

  /** Frosted ice of the given age (clamped to the server's maximum), null when unsupported. */
  private static BlockData frostedCrust(int age) {
    BlockData crust = Material.FROSTED_ICE.createBlockData();

    if (crust instanceof Ageable ice) {
      ice.setAge(Math.max(0, Math.min(age, ice.getMaximumAge())));

      return crust;
    }

    return null;
  }

  /** Frosted ice in the storm: one age per hit, the oldest age turns into real ice. */
  private void growFrosted(Block block) {
    if (!(block.getBlockData() instanceof Ageable ice)) {
      return;
    }

    if (ice.getAge() >= ice.getMaximumAge()) {
      block.setType(Material.ICE, false);
    } else {
      ice.setAge(ice.getAge() + 1);

      block.setBlockData(ice, false);
    }

    changedCount++;

    weatherChanged++;
  }

  /**
   * Frosted ice in the thaw: one age back per hit. The last age turns to water and leaves a
   * floating snow layer on top — the slush stage that melts next, so a bank goes ice -> crust ->
   * snow-on-water -> open water, never in one step.
   */
  private void meltFrosted(Block block) {
    if (!(block.getBlockData() instanceof Ageable ice) || ice.getAge() == 0) {
      block.setType(Material.WATER, false);

      Block above = block.getRelative(BlockFace.UP);

      if (above.getType().isAir()) {
        above.setType(Material.SNOW, false);
      }

      return;
    }

    ice.setAge(ice.getAge() - 1);

    block.setBlockData(ice, false);
  }

  /** True when a player keeps the area loaded — then we neither simulate nor unload. */
  private boolean playersNear(int chunkX, int chunkZ) {
    int margin = world.getSimulationDistance() + 1;

    for (Player player : world.getPlayers()) {
      int dx = (player.getLocation().getBlockX() >> 4) - chunkX;
      int dz = (player.getLocation().getBlockZ() >> 4) - chunkZ;

      if (Math.abs(dx) <= margin && Math.abs(dz) <= margin) {
        return true;
      }
    }

    return false;
  }
}
