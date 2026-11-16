package ru.geekideas.mc.climate;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Biome map in SQLite: plugins/Terrain/biomes.db. Tables and field meanings are in README.md next
 * to the plugin. original_biome is written once during scanning and never changes after that.
 */
final class Store {
  /** One 4×4×4 map cell. */
  record Cell(int sectionY, int cellX, int cellY, int cellZ, String original) {}

  /** Row of the chunks table: what is already scanned and in which state. */
  record ChunkState(int chunkX, int chunkZ, String state) {}

  private final File file;
  private Connection db;

  Store(File file) {
    this.file = file;
  }

  void open() throws SQLException {
    File dir = file.getParentFile();

    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
      throw new SQLException("cannot create " + dir);
    }

    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException err) {
      throw new SQLException("no SQLite driver in the jar", err);
    }

    db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());

    boolean initialized = false;

    try {
      // PRAGMA journal_mode cannot be changed inside a transaction: set the mode first, then
      // autocommit off
      try (Statement st = db.createStatement()) {
        st.execute("PRAGMA journal_mode=WAL");
        st.execute("PRAGMA synchronous=NORMAL");
        st.execute("PRAGMA cache_size=-64000");
      }

      db.setAutoCommit(false);

      try (Statement st = db.createStatement()) {
        st.execute("CREATE TABLE IF NOT EXISTS meta (" + " k TEXT PRIMARY KEY, v TEXT NOT NULL)");

        st.execute(
            "CREATE TABLE IF NOT EXISTS chunks ("
                + " world TEXT NOT NULL,"
                + " chunk_x INTEGER NOT NULL,"
                + " chunk_z INTEGER NOT NULL,"
                + " state TEXT NOT NULL,"
                + " cells_total INTEGER NOT NULL,"
                + " cells_spring INTEGER NOT NULL DEFAULT 0,"
                + " cells_summer INTEGER NOT NULL DEFAULT 0,"
                + " cells_autumn INTEGER NOT NULL DEFAULT 0,"
                + " cells_winter INTEGER NOT NULL DEFAULT 0,"
                + " cells_original INTEGER NOT NULL,"
                + " cells_changed INTEGER NOT NULL,"
                + " scanned_at INTEGER NOT NULL,"
                + " applied_at INTEGER,"
                + " PRIMARY KEY (world, chunk_x, chunk_z))");

        st.execute(
            "CREATE TABLE IF NOT EXISTS cells ("
                + " world TEXT NOT NULL,"
                + " chunk_x INTEGER NOT NULL,"
                + " chunk_z INTEGER NOT NULL,"
                + " section_y INTEGER NOT NULL,"
                + " cell_x INTEGER NOT NULL,"
                + " cell_y INTEGER NOT NULL,"
                + " cell_z INTEGER NOT NULL,"
                + " original_biome TEXT NOT NULL,"
                + " applied_state TEXT NOT NULL,"
                + " PRIMARY KEY (world, chunk_x, chunk_z, section_y, cell_x, cell_y, cell_z))"
                + " WITHOUT ROWID");
      }

      migrate();

      db.commit();

      initialized = true;
    } finally {
      if (!initialized) {
        try {
          db.close();
        } catch (SQLException ignored) {
        }

        db = null;
      }
    }
  }

  /** Adds the counter columns to a database created by an earlier plugin version. */
  private void migrate() throws SQLException {
    Set<String> columns = new HashSet<>();

    try (Statement st = db.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA table_info(chunks)")) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }

    for (String column : List.of("cells_spring", "cells_summer", "cells_autumn", "cells_winter")) {
      if (!columns.contains(column)) {
        try (Statement st = db.createStatement()) {
          st.execute("ALTER TABLE chunks ADD COLUMN " + column + " INTEGER NOT NULL DEFAULT 0");
        }
      }
    }
  }

  void close() {
    if (db == null) {
      return;
    }

    try {
      db.commit();
    } catch (SQLException err) {
      // shutting down at server stop: the last commit may have failed
    } finally {
      try {
        db.close();
      } catch (SQLException ignored) {
      }

      db = null;
    }
  }

  void commit() {
    if (db == null) {
      return;
    }

    try {
      db.commit();
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException("commit: " + err.getMessage(), err);
    }
  }

  String meta(String key) {
    try (PreparedStatement sel = db.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
      sel.setString(1, key);

      try (ResultSet rs = sel.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException("meta " + key + ": " + err.getMessage(), err);
    }
  }

  void deleteMeta(String key) {
    if (db == null) {
      return;
    }

    try (PreparedStatement del = db.prepareStatement("DELETE FROM meta WHERE k = ?")) {
      del.setString(1, key);
      del.executeUpdate();
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
      }

      throw new IllegalStateException("meta " + key + ": " + err.getMessage(), err);
    }
  }

  void putMeta(String key, String value) {
    try (PreparedStatement ins =
        db.prepareStatement(
            "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
      ins.setString(1, key);
      ins.setString(2, value);
      ins.executeUpdate();
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException("meta " + key + ": " + err.getMessage(), err);
    }
  }

  /** Chunk key: high 32 bits — chunk_x, low 32 bits — chunk_z. */
  static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }

  static int chunkX(long key) {
    return (int) (key >> 32);
  }

  static int chunkZ(long key) {
    return (int) key;
  }

  /** All scanned chunks of a world: key → state. */
  /** Sorted chunk keys of a world without the state map — for passes that only need membership. */
  long[] chunkKeys(String world) {
    List<Long> keys = new ArrayList<>();

    try (PreparedStatement sel =
        db.prepareStatement("SELECT chunk_x, chunk_z FROM chunks WHERE world = ?")) {
      sel.setString(1, world);

      try (ResultSet rs = sel.executeQuery()) {
        while (rs.next()) {
          keys.add(chunkKey(rs.getInt(1), rs.getInt(2)));
        }
      }
    } catch (SQLException err) {
      throw new IllegalStateException("chunk keys: " + err.getMessage(), err);
    }

    long[] sorted = keys.stream().mapToLong(Long::longValue).toArray();

    Arrays.sort(sorted);

    return sorted;
  }

  Map<Long, String> chunkMap(String world) {
    Map<Long, String> known = new HashMap<>();

    try (PreparedStatement sel =
        db.prepareStatement("SELECT chunk_x, chunk_z, state FROM chunks WHERE world = ?")) {
      sel.setString(1, world);

      try (ResultSet rs = sel.executeQuery()) {
        while (rs.next()) {
          known.put(chunkKey(rs.getInt(1), rs.getInt(2)), rs.getString(3));
        }
      }
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException("read map: " + err.getMessage(), err);
    }

    return known;
  }

  /** Whether the chunk is already in the map. Primary key lookup, no row scan. */
  boolean hasChunk(String world, int chunkX, int chunkZ) {
    try (PreparedStatement sel =
        db.prepareStatement(
            "SELECT 1 FROM chunks WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
      sel.setString(1, world);
      sel.setInt(2, chunkX);
      sel.setInt(3, chunkZ);
      try (ResultSet rs = sel.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException(
          "chunk " + chunkX + "," + chunkZ + ": " + err.getMessage(), err);
    }
  }

  /** Writes the scan result of a chunk. A re-scan never overwrites the original. */
  void insertChunk(String world, int chunkX, int chunkZ, List<Cell> cells) {
    try {
      try (PreparedStatement chunk =
          db.prepareStatement(
              "INSERT OR IGNORE INTO chunks (world, chunk_x, chunk_z, state, cells_total,"
                  + " cells_original, cells_changed, scanned_at)"
                  + " VALUES (?, ?, ?, 'original', ?, ?, 0, ?)")) {
        chunk.setString(1, world);
        chunk.setInt(2, chunkX);
        chunk.setInt(3, chunkZ);

        int cellCount = cells.size();

        chunk.setInt(4, cellCount);
        chunk.setInt(5, cellCount);
        chunk.setLong(6, System.currentTimeMillis());
        chunk.executeUpdate();
      }

      try (PreparedStatement ins =
          db.prepareStatement(
              "INSERT OR IGNORE INTO cells (world, chunk_x, chunk_z, section_y, cell_x, cell_y,"
                  + " cell_z, original_biome, applied_state)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'original')")) {
        for (Cell cell : cells) {
          ins.setString(1, world);
          ins.setInt(2, chunkX);
          ins.setInt(3, chunkZ);
          ins.setInt(4, cell.sectionY());
          ins.setInt(5, cell.cellX());
          ins.setInt(6, cell.cellY());
          ins.setInt(7, cell.cellZ());
          ins.setString(8, cell.original());
          ins.addBatch();
        }

        ins.executeBatch();
      }
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException(
          "scan chunk " + chunkX + "," + chunkZ + ": " + err.getMessage(), err);
    }
  }

  /** Cells of a chunk in (section_y, cell_y, cell_z, cell_x) order. */
  List<Cell> cells(String world, int chunkX, int chunkZ) {
    List<Cell> cells = new ArrayList<>();

    try (PreparedStatement sel =
        db.prepareStatement(
            "SELECT section_y, cell_x, cell_y, cell_z, original_biome FROM cells"
                + " WHERE world = ? AND chunk_x = ? AND chunk_z = ?"
                + " ORDER BY section_y, cell_y, cell_z, cell_x")) {
      sel.setString(1, world);
      sel.setInt(2, chunkX);
      sel.setInt(3, chunkZ);
      try (ResultSet rs = sel.executeQuery()) {
        while (rs.next()) {
          cells.add(
              new Cell(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5)));
        }
      }
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException(
          "chunk cells " + chunkX + "," + chunkZ + ": " + err.getMessage(), err);
    }

    return cells;
  }

  /** Per-state cell counters (index = Season.ordinal()) and the number of rewritten cells. */
  void putChunkState(
      String world, int chunkX, int chunkZ, String state, int[] cellsByState, int changed) {
    try (PreparedStatement upd =
        db.prepareStatement(
            "UPDATE chunks SET state = ?, cells_spring = ?, cells_summer = ?, cells_autumn = ?,"
                + " cells_winter = ?, cells_original = ?, cells_changed = ?, applied_at = ?"
                + " WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
      upd.setString(1, state);
      upd.setInt(2, cellsByState[Season.SPRING.ordinal()]);
      upd.setInt(3, cellsByState[Season.SUMMER.ordinal()]);
      upd.setInt(4, cellsByState[Season.AUTUMN.ordinal()]);
      upd.setInt(5, cellsByState[Season.WINTER.ordinal()]);
      upd.setInt(6, cellsByState[Season.RESTORE.ordinal()]);
      upd.setInt(7, changed);
      upd.setLong(8, System.currentTimeMillis());
      upd.setString(9, world);
      upd.setInt(10, chunkX);
      upd.setInt(11, chunkZ);
      upd.executeUpdate();
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException(
          "chunk state " + chunkX + "," + chunkZ + ": " + err.getMessage(), err);
    }
  }

  /** state → {chunks, spring, summer, autumn, winter, original, changed by last pass}. */
  Map<String, long[]> totals(String world) {
    Map<String, long[]> totals = new HashMap<>();

    try (PreparedStatement sel =
        db.prepareStatement(
            "SELECT state, COUNT(*), COALESCE(SUM(cells_spring), 0), COALESCE(SUM(cells_summer),"
                + " 0), COALESCE(SUM(cells_autumn), 0), COALESCE(SUM(cells_winter), 0),"
                + " COALESCE(SUM(cells_original), 0), COALESCE(SUM(cells_changed), 0) FROM chunks"
                + " WHERE world = ? GROUP BY state")) {
      sel.setString(1, world);

      try (ResultSet rs = sel.executeQuery()) {
        while (rs.next()) {
          totals.put(
              rs.getString(1),
              new long[] {
                rs.getLong(2),
                rs.getLong(3),
                rs.getLong(4),
                rs.getLong(5),
                rs.getLong(6),
                rs.getLong(7),
                rs.getLong(8)
              });
        }
      }
    } catch (SQLException err) {
      try {
        db.rollback();
      } catch (SQLException ignored) {
        // the transaction is already broken
      }

      throw new IllegalStateException("statistics: " + err.getMessage(), err);
    }

    return totals;
  }

  long fileSize() {
    return file.length() + new File(file.getPath() + "-wal").length();
  }
}
