package ru.geekideas.mc.climate;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Ambient temperature and clothing bonus calculation. */
public final class Env {
  private Env() {}

  public record Result(double temperature, String reason, boolean sheltered) {}

  public static Result of(Player player, FileConfiguration cfg, String season) {
    double t = cfg.getDouble("environment.base", 20.0);

    StringBuilder why = new StringBuilder();

    World world = player.getWorld();

    String biomeKey = world.getBiome(player.getLocation()).getKey().getKey();
    String biomeName = null;

    double biomeDelta = 0;

    ConfigurationSection biomes = cfg.getConfigurationSection("environment.biomes");

    if (biomes != null) {
      for (String key : biomes.getKeys(false)) {
        if (biomeKey.contains(key)) {
          biomeName = key;

          biomeDelta = biomes.getDouble(key);

          break;
        }
      }
    }

    t += biomeDelta;

    if (biomeName != null) {
      why.append(Lang.tr("reason-biome", biomeName));
    }

    double heightStart = cfg.getDouble("environment.height-start", 100);
    double heightPer = cfg.getDouble("environment.height-per", 30);
    double heightStep = cfg.getDouble("environment.height-step", -1);
    double y = player.getLocation().getY();

    if (heightPer > 0 && y > heightStart) {
      t += Math.floor((y - heightStart) / heightPer) * heightStep;

      append(why, Lang.tr("reason-height"));
    }

    double seasonDelta = cfg.getDouble("environment.seasons." + season, 0);

    t += seasonDelta;

    if (seasonDelta != 0) {
      append(why, Lang.tr("reason-season", season.toLowerCase()));
    }

    if (world.hasStorm()
        && world.getHighestBlockYAt(player.getLocation()) <= player.getLocation().getBlockY()) {
      boolean thundering = world.isThundering();

      double weather =
          thundering
              ? cfg.getDouble("environment.weather.thunder", -6)
              : cfg.getDouble("environment.weather.rain", -4);

      t += weather * (1 - weatherResist(player, cfg));

      append(why, Lang.tr(thundering ? "reason-thunder" : "reason-rain"));
    }

    if (player.isInWater()) {
      boolean snowy =
          biomeName != null && (biomeName.contains("snowy") || biomeName.contains("frozen"));

      t +=
          snowy
              ? cfg.getDouble("environment.water-snowy", -12)
              : cfg.getDouble("environment.water", -8);

      append(why, Lang.tr("reason-water"));
    }

    for (String dim : new String[] {"world_the_nether", "world_the_end"}) {
      if (world.getName().endsWith(dim)) {
        double d = cfg.getDouble("environment.dimensions." + dim, 0);

        if (d != 0) {
          t += d;

          append(why, Lang.tr(dim.contains("nether") ? "reason-nether" : "reason-end"));
        }
      }
    }

    // Block underfoot: ice and snow cool the player down.
    ConfigurationSection surface = cfg.getConfigurationSection("environment.surface");

    if (surface != null) {
      Material below = player.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType();

      if (surface.contains(below.name())) {
        t += surface.getDouble(below.name());

        append(why, Lang.tr("reason-surface", below.name().toLowerCase(java.util.Locale.ROOT)));
      }
    }

    int radius = cfg.getInt("environment.heat-radius", 4);

    ConfigurationSection heat = cfg.getConfigurationSection("environment.heat");

    if (heat != null && radius > 0) {
      Set<Material> foundNear = EnumSet.noneOf(Material.class);
      Set<Material> foundFar = EnumSet.noneOf(Material.class);

      Block origin = player.getLocation().getBlock();

      for (int dx = -radius; dx <= radius; dx++) {
        for (int dy = -radius; dy <= radius; dy++) {
          for (int dz = -radius; dz <= radius; dz++) {
            Material m = origin.getRelative(dx, dy, dz).getType();

            if (!heat.contains(m.name())) {
              continue;
            }

            if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1) {
              foundNear.add(m);
            } else {
              foundFar.add(m);
            }
          }
        }
      }

      // A nearby block gives a double bonus; each unique type counts separately,
      // so torch + lantern + furnace together warm more than a single torch.
      for (Material m : foundNear) {
        t += heat.getDouble(m.name()) * 2.0;
      }

      for (Material m : foundFar) {
        t += heat.getDouble(m.name());
      }

      if (!foundNear.isEmpty() || !foundFar.isEmpty()) {
        append(why, Lang.tr(foundNear.isEmpty() ? "reason-heat-near" : "reason-heat-very-near"));
      }
    }

    // House: a roof over the head (sky not visible), at least minWalls horizontal
    // sides closedCount AND some light inside (block light level >= 8).
    // A closedCount side is any block that occludes view (full block, stairs, slab,
    // fence, glass, trapdoor, etc.).
    double shelterBonus = cfg.getDouble("environment.shelter", 10.0);

    int minWalls = Math.max(1, cfg.getInt("environment.shelter-walls", 2));
    int closedCount = 0;

    var eye = player.getEyeLocation().getBlock();

    Material east = eye.getRelative(1, 0, 0).getType();
    Material west = eye.getRelative(-1, 0, 0).getType();
    Material south = eye.getRelative(0, 0, 1).getType();
    Material north = eye.getRelative(0, 0, -1).getType();

    if (isOpaque(east)) {
      closedCount++;
    }

    if (isOpaque(west)) {
      closedCount++;
    }

    if (isOpaque(south)) {
      closedCount++;
    }

    if (isOpaque(north)) {
      closedCount++;
    }

    boolean roof = isOpaque(eye.getRelative(0, 1, 0).getType());
    boolean light = eye.getLightFromBlocks() >= 8;
    boolean sheltered = roof && closedCount >= minWalls && light;

    if (sheltered) {
      t += (20 - t) * Math.min(0.9, shelterBonus / 20.0);

      append(why, Lang.tr("reason-shelter"));
    }

    return new Result(t, why.length() == 0 ? Lang.tr("reason-neutral") : why.toString(), sheltered);
  }

  /** A "view-occluding" block: stairs, slabs, fences, glass, trapdoors all qualify. */
  private static boolean isOpaque(Material material) {
    return material.isOccluding() || material.isSolid();
  }

  /** Weather damping fraction from armor (0..0.75). */
  public static double weatherResist(Player player, FileConfiguration cfg) {
    ConfigurationSection section = cfg.getConfigurationSection("weather-resist");

    if (section == null) {
      return 0;
    }

    double sum = 0;

    for (ItemStack item : player.getInventory().getArmorContents()) {
      if (item != null) {
        sum += section.getDouble(item.getType().name(), 0);
      }
    }

    return Math.min(0.75, sum);
  }

  /** Cooling from (chainmail) armor: pulls body temperature down — helps in heat, hurts in cold. */
  public static double cooling(Player player, FileConfiguration cfg) {
    ConfigurationSection section = cfg.getConfigurationSection("cooling");

    if (section == null) {
      return 0;
    }

    double sum = 0;

    for (ItemStack item : player.getInventory().getArmorContents()) {
      if (item != null) {
        sum += section.getDouble(item.getType().name(), 0);
      }
    }

    return sum;
  }

  /** Total clothing bonus; a wet player gets half of it. */
  public static double clothing(Player player, FileConfiguration cfg, boolean wet) {
    ConfigurationSection section = cfg.getConfigurationSection("clothing");

    if (section == null) {
      return 0;
    }

    double sum = 0;

    for (ItemStack item : player.getInventory().getArmorContents()) {
      if (item != null) {
        sum += section.getDouble(item.getType().name(), 0);
      }
    }

    return wet ? sum / 2 : sum;
  }

  private static void append(StringBuilder sb, String part) {
    if (sb.length() > 0) {
      sb.append(", ");
    }

    sb.append(part);
  }
}
