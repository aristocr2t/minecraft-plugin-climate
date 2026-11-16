package ru.geekideas.mc.climate;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;

/**
 * Builds the biome map. The chunk list comes from the world region files (only existing chunks,
 * nothing is generated) and is walked in rings from world center (0,0) outward. Already scanned
 * chunks are not in the list, so a restart resumes the work on its own.
 */
final class Scanner extends Pass {
  private final List<long[]> planned;
  private final int commitEvery;
  private final int logEvery;
  private int index;
  private int ring = -1;
  private int ringScanCount;
  private int commitInterval;
  private int cellCount;
  private final int mapChunkCount;
  private int failedCount;

  Scanner(
      Terrain plugin,
      World world,
      Store store,
      int maxChunkCount,
      int budgetMillis,
      int radiusBlocks,
      int commitEvery,
      int logEvery) {
    super(plugin, world, store, maxChunkCount, budgetMillis);

    this.commitEvery = Math.max(1, commitEvery);
    this.logEvery = Math.max(1, logEvery);
    this.planned = new ArrayList<>();
    Map<Long, String> known = store.chunkMap(worldName);

    this.mapChunkCount = known.size();

    this.cellCount = this.mapChunkCount * plugin.cellsPerChunk();
    File regionDir = regionDir(world);

    Set<Long> existing = existingChunks(regionDir);

    int limit = radiusBlocks <= 0 ? 0 : radiusBlocks >> 4;

    for (long key : existing) {
      if (known.containsKey(key)) {
        continue; // already in the map: never read twice
      }

      int chunkX = Store.chunkX(key);
      int chunkZ = Store.chunkZ(key);

      if (limit > 0 && (Math.abs(chunkX) > limit || Math.abs(chunkZ) > limit)) {
        continue;
      }

      planned.add(new long[] {Math.max(Math.abs(chunkX), Math.abs(chunkZ)), key});
    }

    planned.sort(Comparator.comparingLong((long[] row) -> row[0]).thenComparingLong(row -> row[1]));

    plugin
        .getLogger()
        .info(
            "[scan] chunks on disk %d, mapped %d, to scan %d,"
                    .formatted(existing.size(), known.size(), planned.size())
                + " rings 0.."
                + (planned.isEmpty() ? "-" : planned.getLast()[0])
                + ", region: "
                + regionDir);
  }

  @Override
  String label() {
    return "scan";
  }

  @Override
  boolean hasPending() {
    return index < planned.size();
  }

  @Override
  void dispatchNext() {
    long[] entry = planned.get(index++);

    int nextRing = (int) entry[0];

    long key = entry[1];

    if (nextRing != ring) {
      if (ring >= 0) {
        store.commit();

        plugin
            .getLogger()
            .info(
                "[scan] ring %d done: %d new chunks, map holds %d chunks / %d cells, next ring %d"
                    .formatted(
                        ring, ringScanCount, mapChunkCount + chunkCount, cellCount, nextRing));
      }

      ring = nextRing;

      ringScanCount = 0;
    }

    int chunkX = Store.chunkX(key);
    int chunkZ = Store.chunkZ(key);

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
                return; // the chunk did not load, or the pass is stopped: don't touch anything
              }

              try {
                scan(chunk);
              } catch (RuntimeException err) {
                plugin
                    .getLogger()
                    .severe("[" + label() + "] chunk " + chunkX + "," + chunkZ + ": " + err);

                stop(); // a database failure must not leave silent holes in the map
              }
            });
  }

  @Override
  void saveMeta() {
    if (failedCount > 0) {
      plugin
          .getLogger()
          .warning("[scan] " + failedCount + " chunks failed; scan progress was not finalized");

      return;
    }

    store.putMeta("scan_ring", String.valueOf(ring));
  }

  private void scan(Chunk chunk) {
    int baseX = chunk.getX() << 4;
    int baseZ = chunk.getZ() << 4;

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

    store.insertChunk(worldName, chunk.getX(), chunk.getZ(), cells);

    chunkCount++;

    ringScanCount++;

    cellCount += cells.size();

    if (chunkCount % logEvery == 0) {
      plugin
          .getLogger()
          .info(
              "[scan] scanning: %d chunks, %d cells, ring %d"
                  .formatted(mapChunkCount + chunkCount, cellCount, ring));
    }

    if (++commitInterval >= commitEvery) {
      store.commit();

      commitInterval = 0;
    }
  }

  @Override
  protected void onFinish() {
    if (failedCount > 0) {
      return; // saveMeta already reported the incomplete scan
    }

    store.putMeta("scan_complete", "1");
  }

  /** Region file directory of the world: Paper stores dimensions separately from the world root. */
  static File regionDir(World world) {
    File folder = world.getWorldFolder();
    File direct = new File(folder, "region");

    if (direct.isDirectory()) {
      return direct;
    }

    String dimension =
        switch (world.getEnvironment()) {
          case NETHER -> "the_nether";
          case THE_END -> "the_end";

          default -> "overworld";
        };

    return new File(new File(folder, "dimensions/minecraft/" + dimension), "region");
  }

  /** Existing (generated) chunks from region file headers. */
  static Set<Long> existingChunks(File regionDir) {
    Set<Long> found = new HashSet<>();

    File[] files = regionDir.listFiles((dir, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));

    if (files == null) {
      return found;
    }

    byte[] header = new byte[4096];

    for (File file : files) {
      String[] parts = file.getName().split("\\.");

      int regionX;
      int regionZ;

      try {
        regionX = Integer.parseInt(parts[1]);
        regionZ = Integer.parseInt(parts[2]);
      } catch (RuntimeException err) {
        continue;
      }

      try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
        raf.readFully(header);
      } catch (IOException err) {
        continue;
      }

      for (int slot = 0; slot < 1024; slot++) {
        int offset =
            ((header[slot * 4] & 0xFF) << 24)
                | ((header[slot * 4 + 1] & 0xFF) << 16)
                | ((header[slot * 4 + 2] & 0xFF) << 8)
                | (header[slot * 4 + 3] & 0xFF);

        if ((offset >>> 8) == 0) {
          continue; // empty slot
        }

        found.add(Store.chunkKey(regionX * 32 + (slot % 32), regionZ * 32 + (slot / 32)));
      }
    }

    return found;
  }
}
