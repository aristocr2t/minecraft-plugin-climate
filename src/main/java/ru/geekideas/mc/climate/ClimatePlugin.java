package ru.geekideas.mc.climate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Climate in one plugin: the seasonal world (biomes, snow, ice, far weather passes) and the player
 * body (temperature, clothing, illness, day rest and night sleep). One command, /climate: the
 * dispatcher routes each subcommand to the world or the player half.
 */
public final class ClimatePlugin extends JavaPlugin {
  /** Subcommands owned by the player half; everything else belongs to the world half. */
  private static final Set<String> PLAYER_SUBS =
      Set.of("status", "reload", "season", "set", "thermometer", "standup", "встать");

  private Terrain terrain;
  private Body thermo;

  @Override
  public void onEnable() {
    saveDefaultConfig();

    terrain = new Terrain(this);
    thermo = new Body(this);

    thermo.start();

    terrain.start();
    if (getCommand("climate") != null) {
      getCommand("climate")
          .setExecutor((sender, command, label, args) -> dispatch(sender, label, args));

      getCommand("climate")
          .setTabCompleter((sender, command, alias, args) -> complete(sender, alias, args));
    }
  }

  /** Reloads world settings without replacing the biome store. */
  public void worldsReload() {
    if (terrain != null) {
      terrain.reload();
    }
  }

  /** True while a world pass holds the biome map — the season clock must not move then. */
  public boolean worldBusy() {
    return terrain != null && terrain.busy();
  }

  /** /climate status shows the body and the world in one answer; the rest routes by subcommand. */
  private boolean dispatch(CommandSender sender, String label, String[] args) {
    String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

    if (sub.equals("status")) {
      // one answer for both halves: the body first, then the world
      thermo.command(sender, null, label, args);

      return terrain.command(sender, null, label, args);
    }

    if (PLAYER_SUBS.contains(sub)) {
      return thermo.command(sender, null, label, args);
    }

    return terrain.command(sender, null, label, args);
  }

  private List<String> complete(CommandSender sender, String alias, String[] args) {
    List<String> a = terrain.complete(sender, null, alias, args);
    List<String> b = thermo.complete(sender, null, alias, args);

    if (a == null || a.isEmpty()) {
      return b;
    }

    if (b == null || b.isEmpty()) {
      return a;
    }

    LinkedHashSet<String> merged = new LinkedHashSet<>(a);

    merged.addAll(b);

    return new ArrayList<>(merged);
  }

  @Override
  public void onDisable() {
    if (thermo != null) {
      try {
        thermo.stop();
      } catch (Throwable t) {
        getLogger().warning("Thermo stop: " + t);
      }
    }

    if (terrain != null) {
      try {
        terrain.stop();
      } catch (Throwable t) {
        getLogger().warning("Terrain stop: " + t);
      }
    }
  }
}
