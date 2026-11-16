package ru.geekideas.mc.climate;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

/**
 * Base background pass: a batch of chunks per tick with a time budget. Chunk loading is
 * asynchronous; biome work happens in the callback on the main thread.
 */
abstract class Pass implements Runnable {
  protected final Terrain plugin;
  protected final World world;
  protected final Store store;
  protected final String worldName;
  private final int maxChunkCount;
  private final long budgetNanos;
  private final long startedAt = System.currentTimeMillis();
  private BukkitTask task;
  private boolean finished;
  private long lastActivity = System.currentTimeMillis();
  protected int inFlightCount;
  protected int chunkCount;
  protected int changedCount;

  /** A completed chunk callback is progress; scheduler ticks alone are not. */
  protected final void chunkCompleted() {
    inFlightCount--;

    lastActivity = System.currentTimeMillis();
  }

  Pass(Terrain plugin, World world, Store store, int maxChunkCount, int budgetMillis) {
    this.plugin = plugin;
    this.world = world;
    this.store = store;
    this.worldName = world.getName();
    this.maxChunkCount = Math.max(1, maxChunkCount);
    this.budgetNanos = Math.max(1, budgetMillis) * 1_000_000L;
  }

  abstract String label();

  /** Whether the pass appears in /climate status as an operation (background passes don't). */
  boolean countsAsOperation() {
    return true;
  }

  /** Whether there is anything left to process (chunks already in flight not counted). */
  abstract boolean hasPending();

  /** Starts the next chunk; may start nothing if the chunk is already in the map. */
  abstract void dispatchNext();

  /** Persists cumulative state to disk (ring metadata, etc.). */
  void saveMeta() {}

  /** Called only on normal pass completion (not on stop). */
  void onFinish() {}

  String progress() {
    return Lang.tr("progress", String.valueOf(chunkCount), String.valueOf(changedCount));
  }

  void start() {
    task = Bukkit.getScheduler().runTaskTimer(plugin.host(), this, 1L, 1L);
  }

  boolean isFinished() {
    return finished;
  }

  void stop() {
    if (finished) {
      return;
    }

    finished = true;

    if (task != null) {
      task.cancel();
    }

    try {
      saveMeta();

      store.commit();
    } catch (RuntimeException err) {
      plugin.getLogger().severe("[" + label() + "] failed to save progress: " + err);
    }

    plugin.getLogger().info("[" + label() + "] stopped: " + progress() + ", progress saved");
    plugin.passFinished(this, Lang.tr("summary-stopped", progress()));
  }

  @Override
  public final void run() {
    if (finished) {
      return;
    }

    if (inFlightCount > 0 && System.currentTimeMillis() - lastActivity > 120_000) {
      plugin.getLogger().severe("[" + label() + "] no chunk progress for 120 s, aborting the pass");

      stop();

      return;
    }

    long deadline = System.nanoTime() + budgetNanos;

    try {
      while (inFlightCount < maxChunkCount && System.nanoTime() < deadline && hasPending()) {
        dispatchNext();
      }

      if (inFlightCount == 0 && !hasPending()) {
        finished = true;

        if (task != null) {
          task.cancel();
        }

        saveMeta();
        onFinish();

        store.commit();

        String summary =
            Lang.tr(
                "summary-done",
                progress(),
                String.valueOf((System.currentTimeMillis() - startedAt) / 1000));

        plugin.getLogger().info("[" + label() + "] finished: " + summary);
        plugin.passFinished(this, summary);
      }
    } catch (RuntimeException err) {
      finished = true;

      if (task != null) {
        task.cancel();
      }

      plugin.getLogger().severe("[" + label() + "] error, pass aborted: " + err);

      err.printStackTrace();

      try {
        store.commit();
      } catch (RuntimeException ignored) {
        // the database is already failing, don't report twice
      }

      plugin.passFinished(this, Lang.tr("summary-error", String.valueOf(err.getMessage())));
    }
  }
}
