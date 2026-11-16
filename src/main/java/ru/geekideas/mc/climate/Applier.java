package ru.geekideas.mc.climate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

/**
 * Applies a season over the map: reads original_biome from the database and sets the target biome
 * in the world. No new cells appear — the Scanner builds the map.
 */
final class Applier extends Pass {
  private final Season season;
  private final Map<String, String> goals;
  private final List<String> names;
  private final List<long[]> queue;
  private final int commitEvery;
  private int index;
  private int commitInterval;
  private int failedCount;

  Applier(
      Terrain plugin,
      World world,
      Store store,
      Season season,
      int maxChunkCount,
      int budgetMillis,
      int radiusBlocks,
      int commitEvery) {
    super(plugin, world, store, maxChunkCount, budgetMillis);

    this.season = season;
    this.goals = plugin.goals(season);
    this.names = new ArrayList<>(goals.keySet());
    this.commitEvery = Math.max(1, commitEvery);

    int limit = radiusBlocks <= 0 ? 0 : radiusBlocks >> 4;

    this.queue = new ArrayList<>();

    for (Map.Entry<Long, String> entry : store.chunkMap(worldName).entrySet()) {
      if (season.state.equals(entry.getValue())) {
        continue; // already in this state
      }

      int chunkX = Store.chunkX(entry.getKey());
      int chunkZ = Store.chunkZ(entry.getKey());

      if (limit > 0 && (Math.abs(chunkX) > limit || Math.abs(chunkZ) > limit)) {
        continue;
      }

      queue.add(new long[] {chunkX, chunkZ});
    }

    queue.sort(Comparator.comparingLong((long[] pos) -> pos[0] * pos[0] + pos[1] * pos[1]));
  }

  /**
   * Seasonal ground: grass by season. Not touched in summer. In winter grass is removed, in autumn
   * tall → short, in spring dirt and podzol are seeded with grass. Snow and ice are NOT touched
   * here on purpose: far-weather melts them gradually in chunks without players; near players
   * vanilla rules apply.
   */
  private void groundChunk(Chunk chunk, int perChunk) {
    // Bounded work: random columns scanned from the surface downwards, never a
    // full 71k-block sweep. Summer and restore do no ground work at all.
    if (season == Season.SUMMER || season == Season.RESTORE) {
      return;
    }

    int doneCount = 0;
    int attemptCount = 0;

    java.util.Random random =
        new java.util.Random(
            chunk.getWorld().getSeed()
                ^ ((long) chunk.getX() << 32 | (chunk.getZ() & 0xffffffffL)));

    var snapshot = chunk.getChunkSnapshot();

    while (doneCount < perChunk && attemptCount < perChunk * 4) {
      attemptCount++;

      int dx = random.nextInt(16);
      int dz = random.nextInt(16);
      int y = snapshot.getHighestBlockYAt(dx, dz);

      for (int step = 0;
          step < 10 && y - step >= world.getMinHeight() && doneCount < perChunk;
          step++) {
        Block block = chunk.getBlock(dx, y - step, dz);

        Material type = block.getType();

        switch (season) {
          case AUTUMN -> {
            if (plugin.grassAutumn && type == Material.TALL_GRASS) {
              block.setType(Material.SHORT_GRASS, false);

              doneCount++;
            }
          }

          case WINTER -> {
            if (plugin.grassWinter
                && (type == Material.TALL_GRASS || type == Material.SHORT_GRASS)
                && random.nextDouble() < plugin.grassWinterChance) {
              block.setType(Material.AIR, false);

              doneCount++;
            }
          }

          case SPRING -> {
            if (!plugin.grassSpring || random.nextDouble() >= plugin.grassSpringChance) {
              break;
            }

            if (type == Material.DIRT && random.nextDouble() < plugin.grassSpringTurfChance) {
              // winter buried the grass: spring re-turfs the scars directly
              block.setType(Material.GRASS_BLOCK, false);

              doneCount++;
            } else if (type == Material.GRASS_BLOCK
                || type == Material.DIRT
                || type == Material.PODZOL) {
              Block above = block.getRelative(org.bukkit.block.BlockFace.UP);

              if (above.getType().isAir()) {
                above.setType(Material.SHORT_GRASS, false);

                doneCount++;
              }
            }
          }

          default -> {}
        }
      }
    }
  }

  @Override
  String label() {
    return season.name().toLowerCase(java.util.Locale.ROOT);
  }

  @Override
  void onFinish() {
    if (failedCount > 0) {
      plugin
          .getLogger()
          .warning(
              "["
                  + label()
                  + "] "
                  + failedCount
                  + " chunks failed; season metadata was not finalized");

      return;
    }

    // The scanner must not run while a season is applied — it would store the
    // seasonal biome as original_biome and restore could never undo it.
    if (season == Season.SPRING || season == Season.RESTORE) {
      store.deleteMeta("season_applied");
      store.deleteMeta("season_name");
    } else {
      store.putMeta("season_applied", "1");
      // Far weather reads the name back to season chunks generated later.
      store.putMeta("season_name", season.state);
    }
  }

  int planned() {
    return queue.size();
  }

  /** Original biome per cell key (cellX, quartY, cellZ packed), for neighbor lookups. */
  private static Map<Long, String> indexOriginals(List<Store.Cell> cells) {
    Map<Long, String> map = new HashMap<>();

    for (Store.Cell cell : cells) {
      map.put(cellKey(cell.cellX(), cell.cellY(), cell.cellZ()), cell.original());
    }

    return map;
  }

  private static long cellKey(int cellX, int quartY, int cellZ) {
    return (quartY * 4L + cellZ) * 4L + cellX;
  }

  /** True when any horizontal neighbor cell of the water is an arid biome. */
  /** True when any horizontal neighbor cell at the same height is an arid biome. */
  private boolean aridNear(
      int quartX,
      int quartZ,
      int quartY,
      int chunkX,
      int chunkZ,
      Map<Long, String> originals,
      Map<Long, List<Store.Cell>> neighborCache) {
    for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
      int nx = quartX + step[0];
      int nz = quartZ + step[1];

      Map<Long, String> source = neighborMap(nx, nz, chunkX, chunkZ, originals, neighborCache);

      String original = source.get(cellKey(Math.floorMod(nx, 4), quartY, Math.floorMod(nz, 4)));

      if (original != null && plugin.aridBiomeSet.contains(original)) {
        return true;
      }
    }

    return false;
  }

  /**
   * True when the shore next to this ocean cell stays open: a neighbor that is arid land itself, or
   * a beach / river mouth with arid land behind it.
   */
  private boolean openShoreNear(
      Store.Cell cell,
      int chunkX,
      int chunkZ,
      Map<Long, String> originals,
      Map<Long, List<Store.Cell>> neighborCache) {
    int quartX = chunkX * 4 + cell.cellX();
    int quartZ = chunkZ * 4 + cell.cellZ();

    for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
      int nx = quartX + step[0];
      int nz = quartZ + step[1];
      int nChunkX = Math.floorDiv(nx, 4);
      int nChunkZ = Math.floorDiv(nz, 4);
      Map<Long, String> source = neighborMap(nx, nz, chunkX, chunkZ, originals, neighborCache);

      String original =
          source.get(cellKey(Math.floorMod(nx, 4), cell.cellY(), Math.floorMod(nz, 4)));

      if (original == null) {
        continue;
      }

      if (plugin.aridBiomeSet.contains(original)) {
        return true;
      }

      if ("beach".equals(original) || "river".equals(original)) {
        if (aridNear(nx, nz, cell.cellY(), nChunkX, nChunkZ, source, neighborCache)) {
          return true;
        }
      }
    }

    return false;
  }

  /** The originals map of the chunk holding the given world quart coordinates. */
  private Map<Long, String> neighborMap(
      int quartX,
      int quartZ,
      int chunkX,
      int chunkZ,
      Map<Long, String> originals,
      Map<Long, List<Store.Cell>> neighborCache) {
    int nChunkX = Math.floorDiv(quartX, 4);
    int nChunkZ = Math.floorDiv(quartZ, 4);

    if (nChunkX == chunkX && nChunkZ == chunkZ) {
      return originals;
    }

    long key = Store.chunkKey(nChunkX, nChunkZ);

    List<Store.Cell> neighborCells =
        neighborCache.computeIfAbsent(
            key, k -> store.cells(worldName, Store.chunkX(k), Store.chunkZ(k)));

    return indexOriginals(neighborCells);
  }

  @Override
  boolean hasPending() {
    return index < queue.size();
  }

  @Override
  void dispatchNext() {
    long[] pos = queue.get(index++);

    int chunkX = (int) pos[0];
    int chunkZ = (int) pos[1];

    boolean wasLoaded = world.isChunkLoaded(chunkX, chunkZ);

    inFlightCount++;

    world
        .getChunkAtAsync(chunkX, chunkZ, false)
        .whenComplete(
            (chunk, error) -> {
              chunkCompleted();

              if (error != null) {
                failedCount++;

                plugin
                    .getLogger()
                    .warning("[" + label() + "] chunk " + chunkX + "," + chunkZ + ": " + error);

                return;
              }

              if (chunk == null || isFinished()) {
                return; // the chunk no longer exists, or the pass is stopped
              }

              try {
                apply(chunk, chunkX, chunkZ);
              } catch (RuntimeException err) {
                plugin
                    .getLogger()
                    .severe("[" + label() + "] chunk " + chunkX + "," + chunkZ + ": " + err);

                stop(); // the season must not be half-applied and marked doneCount
              }

              if (!wasLoaded) {
                world.unloadChunkRequest(chunkX, chunkZ);
              }
            });
  }

  private void apply(Chunk chunk, int chunkX, int chunkZ) {
    List<Store.Cell> cells = store.cells(worldName, chunkX, chunkZ);

    if (cells.isEmpty()) {
      return;
    }

    int baseX = chunkX << 4;
    int baseZ = chunkZ << 4;
    int writtenCount = 0;
    int mappedCount = 0;
    Map<Long, String> originals = indexOriginals(cells);

    Map<Long, List<Store.Cell>> neighborCache = new HashMap<>();

    for (Store.Cell cell : cells) {
      String goal = goals.get(cell.original());

      if (goal == null) {
        continue; // "native" icy or unchangeable biome
      }

      // Arid shores stay open: a river running through a savanna and its beach
      // never freeze, and the ocean stays open along such a beach.
      if ((cell.original().equals("river") && goal.equals("frozen_river"))
          || (cell.original().equals("beach") && goal.equals("snowy_beach"))) {
        if (aridNear(
            chunkX * 4 + cell.cellX(),
            chunkZ * 4 + cell.cellZ(),
            cell.cellY(),
            chunkX,
            chunkZ,
            originals,
            neighborCache)) {
          continue;
        }
      } else if (cell.original().equals("ocean") && goal.equals("frozen_ocean")) {
        if (openShoreNear(cell, chunkX, chunkZ, originals, neighborCache)) {
          continue;
        }
      }

      mappedCount++;

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

    if (plugin.thawEnabled) {
      groundChunk(chunk, plugin.thawPerChunk);
    }

    int[] cellsByState = new int[Season.values().length];

    int cellCount = cells.size();

    if (season == Season.SPRING || season == Season.RESTORE) {
      cellsByState[Season.RESTORE.ordinal()] = cellCount;
    } else {
      cellsByState[season.ordinal()] = mappedCount;

      cellsByState[Season.RESTORE.ordinal()] = cellCount - mappedCount;
    }

    store.putChunkState(worldName, chunkX, chunkZ, season.state, cellsByState, writtenCount);

    chunkCount++;

    changedCount += writtenCount;

    if (chunkCount % 500 == 0) {
      plugin
          .getLogger()
          .info("[" + label() + "] " + chunkCount + " chunks, " + changedCount + " cells changed");
    }

    if (++commitInterval >= commitEvery) {
      store.commit();

      commitInterval = 0;
    }
  }
}
