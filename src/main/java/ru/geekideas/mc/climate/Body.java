package ru.geekideas.mc.climate;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class Body implements Listener {
  private final ru.geekideas.mc.climate.ClimatePlugin plugin;

  public Body(ru.geekideas.mc.climate.ClimatePlugin plugin) {
    this.plugin = plugin;
  }

  /** The hosting JavaPlugin: scheduler, server and metadata live behind it. */
  public ru.geekideas.mc.climate.ClimatePlugin host() {
    return plugin;
  }

  public java.util.logging.Logger getLogger() {
    return plugin.getLogger();
  }

  /** Blizzard flakes fall a little on the way instead of flying perfectly flat. */
  private static final double BLIZZARD_FALL = -0.15;

  private record State(
      String id, double max, double damage, int damageSeconds, List<String> effects) {}

  private final Map<UUID, Double> bodyMap = new HashMap<>();
  private final Map<UUID, BossBar> barMap = new HashMap<>();
  private final Map<UUID, BossBar> illnessBarMap = new HashMap<>();
  private final Map<UUID, Integer> outsideTickMap = new HashMap<>();
  private final Map<UUID, Integer> illnessTickMap = new HashMap<>();
  private final Map<UUID, Long> illnessUntilMap = new HashMap<>();
  private final Map<UUID, String> illnessSeasonMap = new HashMap<>();
  private final Map<UUID, Integer> damageClockMap = new HashMap<>();
  private final Map<UUID, Integer> actionClockMap = new HashMap<>();
  private final Map<UUID, org.bukkit.scheduler.BukkitTask> wakeTaskMap = new HashMap<>();
  private final Map<UUID, org.bukkit.Location> lastPositionMap = new HashMap<>();
  private final Map<UUID, Long> lastMineMap = new HashMap<>();

  private final Map<UUID, org.bukkit.Location> dayRestMap =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final List<State> states = new ArrayList<>();
  private final List<String> illnessEffects = new ArrayList<>();
  private NamespacedKey thermometerKey;
  private NamespacedKey bodyKey;
  private NamespacedKey illnessKey;
  private File seasonFile;
  private String season = "WINTER";
  private int tickInterval = 20;

  private int elapsedSeasonSeconds;

  // Shared wind clock and the last blizzard gust (millis).
  private double windCycleDays = 3;
  private long lastGust;

  public void start() {
    plugin.saveDefaultConfig();

    Lang.load(plugin);

    thermometerKey = new NamespacedKey(plugin, "thermometer");
    bodyKey = new NamespacedKey(plugin, "body");
    illnessKey = new NamespacedKey(plugin, "illness_until");

    loadStates();
    loadSeason();
    updateThermometerRecipe();

    plugin.getServer().getPluginManager().registerEvents(this, plugin);

    tickInterval = Math.max(5, plugin.getConfig().getInt("interval-ticks", 20));

    int interval = tickInterval;

    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    Bukkit.getScheduler().runTaskTimer(plugin, this::seasonTick, 20L, 20L);

    Bukkit.getScheduler()
        .runTaskTimer(
            plugin,
            this::weatherDirector,
            1200L,
            Math.max(200, plugin.getConfig().getInt("weather-director.roll-every-ticks", 1200)));

    windCycleDays = Math.max(0.05, plugin.getConfig().getDouble("wind.cycle-days", 3));

    Bukkit.getScheduler().runTaskTimer(plugin, this::blizzardTick, 40L, 2L);

    if (plugin.getConfig().getConfigurationSection("blizzard") == null) {
      // An upgraded install keeps its old config.yml: the section has to be merged in.
      plugin
          .getLogger()
          .warning(
              "config.yml has no blizzard section: copy it from the jar to enable the winter"
                  + " blizzard");
    }

    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              World world = overworld();

              Integer percentage =
                  world == null
                      ? null
                      : world.getGameRuleValue(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE);

              if (dayRestMap.isEmpty() && percentage != null && percentage == 101) {
                world.setGameRule(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE, 100);
              }
            },
            40L);

    for (Player p : Bukkit.getOnlinePlayers()) {
      join(p);
    }

    plugin
        .getLogger()
        .info(
            "Enabling body core "
                + plugin.getPluginMeta().getVersion()
                + " (season "
                + season
                + ")");
  }

  public void stop() {
    for (Player online : Bukkit.getOnlinePlayers()) {
      removeDayRestButton(online);
    }

    releaseSleepingSkip();
    saveSeason();
    for (BossBar bar : barMap.values()) {
      bar.removeAll();
    }

    barMap.clear();
    for (BossBar bar : illnessBarMap.values()) {
      bar.removeAll();
    }

    illnessBarMap.clear();
  }

  // ---------------------------------------------------------------- states

  private void loadStates() {
    states.clear();

    for (Map<?, ?> raw : plugin.getConfig().getMapList("states")) {
      String id = String.valueOf(raw.get("id"));

      double max = raw.get("max") instanceof Number n ? n.doubleValue() : 99;
      double damage = raw.get("damage") instanceof Number n ? n.doubleValue() : 0;

      int seconds = raw.get("damage-seconds") instanceof Number n ? n.intValue() : 0;

      List<String> effects = new ArrayList<>();

      if (raw.get("effects") instanceof List<?> list) {
        for (Object e : list) {
          if (e instanceof Map<?, ?> m && m.get("type") != null) {
            String type = String.valueOf(m.get("type"));

            int amp = m.get("amplifier") instanceof Number a ? a.intValue() : 0;

            effects.add(type + ":" + amp);
          }
        }
      }

      states.add(new State(id, max, damage, seconds, effects));
    }

    if (states.isEmpty()) {
      states.add(new State("normal", 99, 0, 0, List.of()));
    }

    illnessEffects.clear();

    for (Object raw : plugin.getConfig().getList("illness.effects", List.of())) {
      if (raw instanceof Map<?, ?> m && m.get("type") != null) {
        String type = String.valueOf(m.get("type"));

        int amp = m.get("amplifier") instanceof Number a ? a.intValue() : 0;

        String duration = m.get("duration-seconds") instanceof Number d ? ":" + d.intValue() : "";

        illnessEffects.add(type + ":" + amp + duration);
      } else if (raw instanceof String entry) {
        illnessEffects.add(entry);
      }
    }

    if (illnessEffects.isEmpty()) {
      illnessEffects.addAll(List.of("NAUSEA:0", "WEAKNESS:0", "HUNGER:0"));
    }
  }

  /** Display label of a bodyMap state, localized via lang/state-<id>. */
  private static String label(State state) {
    return Lang.tr("state-" + state.id(), state.id());
  }

  private State stateOf(double temperature) {
    for (State s : states) {
      if (temperature <= s.max()) {
        return s;
      }
    }

    return states.get(states.size() - 1);
  }

  private PotionEffectType effectType(String name) {
    return switch (name) {
      case "SLOWNESS" -> PotionEffectType.SLOWNESS;
      case "NAUSEA" -> PotionEffectType.NAUSEA;
      case "HUNGER" -> PotionEffectType.HUNGER;
      case "WEAKNESS" -> PotionEffectType.WEAKNESS;
      case "MINING_FATIGUE" -> PotionEffectType.MINING_FATIGUE;
      case "SPEED" -> PotionEffectType.SPEED;

      default -> null;
    };
  }

  private void applyEffects(Player player, List<String> effects) {
    for (String entry : effects) {
      String[] parts = entry.split(":");

      PotionEffectType type = effectType(parts[0]);

      if (type == null) {
        continue;
      }

      int amp = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

      player.addPotionEffect(new PotionEffect(type, 60, amp, true, false, true));
    }
  }

  // ---------------------------------------------------------------- temperature tick

  private void tick() {
    FileConfiguration cfg = plugin.getConfig();

    double base = cfg.getDouble("body-base", 36.6);
    double factor = cfg.getDouble("body-factor", 0.22);

    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID id = player.getUniqueId();

      // Creative and spectator players always stay at normal temperature.
      GameMode mode = player.getGameMode();

      if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
        bodyMap.put(id, base);

        State normal = stateOf(base);

        updateBar(player, base, normal);

        updateActionBar(
            player,
            base,
            normal,
            Env.of(player, cfg, season),
            Env.clothing(player, cfg, player.isInWater()));

        continue;
      }

      Env.Result env = Env.of(player, cfg, season);

      boolean wet = player.isInWater();

      double warmth = Env.clothing(player, cfg, wet);

      ItemStack[] armor = player.getInventory().getArmorContents();

      int pieces = 0;

      for (ItemStack item : armor) {
        if (item != null) {
          pieces++;
        }
      }

      boolean fullSet = pieces >= 4;
      boolean hasHelmet = armor[3] != null;
      boolean sheltered = env.sheltered();
      boolean mining = isMining(id, cfg);
      boolean active = mining || hasMoved(id, player);

      // Directional clothing model, close to the real world: in the cold warmth
      // insulates (leather/netherite are warm suits, iron/diamond warm a bit),
      // in the heat the same armor works against you. Movement and mining
      // generate heat in the cold and add discomfort in the heat. Indoors
      // clothing decides comfort: dressed in a winter house and naked in a
      // summer house both give 36.6; dressing against the season brings
      // nothing worse than mild slowness. Real danger lives outside.
      double deviation = env.temperature() - 20;
      double delta;

      if (deviation < 0) {
        double insulation =
            Math.min(0.9, warmth * cfg.getDouble("clothing.insulation-per-point", 0.19));

        delta = deviation * factor * (1 - insulation);

        if (!sheltered && !fullSet) {
          // winter outside wants a full set of armor
          delta -= (4 - pieces) * cfg.getDouble("cold.missing-piece-penalty", 0.6);
        }

        if (sheltered) {
          // a dressed player feels just right at home; the naked at worst
          // get cool (mild slowness) — a house never lets you freeze
          delta =
              Math.max(delta * (1 - warmth / 4), -cfg.getDouble("cold.shelter-min-deviation", 1.6));
        }

        if (active) {
          delta +=
              mining
                  ? cfg.getDouble("activity.mine-bonus", 2.5)
                  : cfg.getDouble("activity.move-bonus", 2.0);
        }
      } else {
        delta = deviation * factor * (1 + warmth * cfg.getDouble("heat.armor-heat-factor", 0.12));

        if (active) {
          delta += cfg.getDouble("heat.move-heat", 1.5);
        }

        if (sheltered) {
          // light clothing breathes indoors: naked at home in summer is
          // comfortable, armor traps the heat (capped at mild discomfort)
          delta =
              Math.min(
                  delta * Math.min(1.0, warmth / 4), cfg.getDouble("heat.shelter-max-delta", 4.0));

        } else if (!hasHelmet && env.temperature() >= cfg.getDouble("heat.helmet-from-env", 25)) {
          delta += cfg.getDouble("heat.helmet-bonus", 3.0); // the summer sun hits a bare head
        }
      }

      int food = player.getFoodLevel();

      if (food >= 18) {
        delta += cfg.getDouble("factors.well-fed", 0.4);
      } else if (food <= 6) {
        delta += cfg.getDouble("factors.hungry", -0.6);
      }

      if (player.isSprinting()) {
        delta += cfg.getDouble("factors.sprinting", 1.0);
      }

      if (wet) {
        delta += cfg.getDouble("factors.wet", -1.0);
      }

      delta -= Env.cooling(player, cfg);

      if (deviation < 0) {
        // In the cold, activity only compensates the frost: running in leather
        // in winter keeps you at ~36.6, never heats you up.
        delta = Math.min(delta, cfg.getDouble("cold.activity-cap", 0.2));
      }

      double target = Math.max(25.0, Math.min(41.5, base + delta));

      // Exponential drift: the body closes a fraction of the gap to the target
      // every second — fast at first, then it settles near the target. That is
      // what makes movement matter: a moving player has a shallower target.
      double drift =
          cfg.getDouble("body-drift-per-second", 0.006)
              * Math.max(1, cfg.getInt("interval-ticks", 20))
              / 20.0;

      // Sleeping and lying on a bed pull the body back to normal.
      if (player.isSleeping()) {
        target = base;

        drift *= cfg.getDouble("rest.sleep-multiplier", 4.0);
      } else if (player.isSneaking() && isOnBed(player)) {
        target = base;

        drift *= cfg.getDouble("rest.bed-multiplier", 2.0);
      } else if (nearBeacon(player, cfg)) {
        target = base;

        drift *= cfg.getDouble("beacon.multiplier", 6.0);
      }

      // Recovery toward normal is much faster in a favorable environment
      // (indoors or a comfortable ambient band): 34.0 -> 36.6 in ~26 seconds.
      double current = bodyMap.getOrDefault(id, target);

      boolean recovering =
          (current < base && target > current) || (current > base && target < current);

      double envT = env.temperature();

      boolean favorable =
          sheltered
              || (envT >= cfg.getDouble("recovery.comfortable-min", 18.0)
                  && envT <= cfg.getDouble("recovery.comfortable-max", 24.0));

      if (recovering && favorable) {
        drift *= cfg.getDouble("recovery.multiplier", 25.0);
      }

      double gap = target - current;
      double value = current + Math.max(-Math.abs(gap), Math.min(Math.abs(gap), drift * gap));

      if (!Double.isFinite(value)) {
        value = base; // a corrupted reading must never stick
      }

      bodyMap.put(id, value);

      State state = stateOf(value);

      applyEffects(player, state.effects());
      applyDamage(player, state);
      applyIllness(player);

      ConfigurationSection illnessCfg = plugin.getConfig().getConfigurationSection("illness");

      if (illnessCfg != null) {
        cureIllness(player, illnessCfg);
      }

      updateBar(player, value, state);
      updateActionBar(player, value, state, env, warmth);
    }
  }

  /** True when the player has moved horizontally since the previous temperature tick. */
  private boolean hasMoved(UUID id, Player player) {
    org.bukkit.Location previous = lastPositionMap.put(id, player.getLocation().clone());

    if (previous == null || !previous.getWorld().equals(player.getWorld())) {
      return false;
    }

    double dx = previous.getX() - player.getLocation().getX();
    double dz = previous.getZ() - player.getLocation().getZ();

    return dx * dx + dz * dz >= 0.0004; // ~0.02 blocks per tick
  }

  /** True within a few seconds after the player broke a block: digging warms you up. */
  private boolean isMining(UUID id, FileConfiguration cfg) {
    Long at = lastMineMap.get(id);

    return at != null
        && System.currentTimeMillis() - at < cfg.getInt("activity.mine-seconds", 5) * 1000L;
  }

  /** The player is standing/lying on a bed: the block under or inside them is a bed. */
  private boolean isOnBed(Player player) {
    var loc = player.getLocation();

    return isBed(loc.getBlock().getType())
        || isBed(loc.clone().subtract(0, 1, 0).getBlock().getType());
  }

  /** Beacon nearby: scan step is 2 blocks so we don't scan thousands of blocks every tick. */
  private boolean nearBeacon(Player player, FileConfiguration cfg) {
    int radius = cfg.getInt("beacon.radius", 8);

    if (radius <= 0) {
      return false;
    }

    var origin = player.getLocation().getBlock();

    for (int dx = -radius; dx <= radius; dx += 2) {
      for (int dy = -radius; dy <= radius; dy += 2) {
        for (int dz = -radius; dz <= radius; dz += 2) {
          if (origin.getRelative(dx, dy, dz).getType() == Material.BEACON) {
            return true;
          }
        }
      }
    }

    return false;
  }

  private boolean isBed(Material material) {
    return material.name().endsWith("_bed");
  }

  private void applyDamage(Player player, State state) {
    UUID id = player.getUniqueId();

    if (state.damage() <= 0 || state.damageSeconds() <= 0) {
      damageClockMap.remove(id);

      return;
    }

    int clock = damageClockMap.merge(id, tickInterval, Integer::sum);

    if (clock >= state.damageSeconds() * 20) {
      damageClockMap.put(id, 0);

      player.damage(state.damage());
    }
  }

  private void applyIllness(Player player) {
    ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("illness");

    if (cfg == null || !cfg.getBoolean("enabled", true)) {
      return;
    }

    UUID id = player.getUniqueId();

    double value = bodyMap.getOrDefault(id, 36.6);

    boolean outside = value < 34.0 || value > 40.0;

    int limit = Math.max(1, cfg.getInt("seconds-outside", 60)) * 20;

    if (!outside) {
      outsideTickMap.remove(id);

      return;
    }

    int ticks =
        outsideTickMap.merge(id, plugin.getConfig().getInt("interval-ticks", 20), Integer::sum);

    if (ticks < limit) {
      return;
    }

    outsideTickMap.remove(id);

    // Extremely rare: about once per hour of continuous abnormal temperature; a repeat in the same
    // season is even rarer.
    if (Math.random() >= 0.02) {
      return;
    }

    if (illnessSeasonMap.getOrDefault(id, "").equals(season)) {
      if (Math.random() >= 0.2) {
        return;
      }
    }

    illnessSeasonMap.put(id, season);

    giveIllness(player, cfg.getInt("duration", 3), Lang.tr("illness-got"));
  }

  private void applyIllnessEffects(Player player, int defaultTicks) {
    for (String entry : illnessEffects) {
      String[] parts = entry.split(":");

      PotionEffectType type = effectType(parts[0]);

      if (type == null) {
        continue;
      }

      int amplifier = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      int ticks = parts.length > 2 ? Math.max(1, Integer.parseInt(parts[2]) * 20) : defaultTicks;

      player.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, false, true));
    }
  }

  /** Applies the illness: lasts for days, cured only by rest and by chance. */
  private void giveIllness(Player player, int days, String message) {
    UUID id = player.getUniqueId();

    int ticks = Math.max(1, days) * 24000;

    applyIllnessEffects(player, ticks);

    long until = System.currentTimeMillis() + ticks * 50L;

    illnessUntilMap.put(id, until);

    player.getPersistentDataContainer().set(illnessKey, PersistentDataType.LONG, until);

    BossBar bar =
        illnessBarMap.computeIfAbsent(
            id,
            k -> {
              BossBar created = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);

              created.addPlayer(player);

              return created;
            });

    bar.setTitle(Lang.tr("illness-title"));
    bar.setProgress(1.0);
    bar.setColor(BarColor.BLUE);
    bar.setVisible(true);

    player.sendActionBar(Component.text(message));
  }

  /**
   * Curing: only in bed. The chance is higher at night; during the day it is half while just lying
   * around.
   */
  private void cureIllness(Player player, ConfigurationSection cfg) {
    UUID id = player.getUniqueId();

    long until = illnessUntilMap.getOrDefault(id, 0L);

    if (until <= 0) {
      return;
    }

    BossBar bar =
        illnessBarMap.computeIfAbsent(
            id,
            k -> {
              BossBar created = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);

              created.addPlayer(player);

              return created;
            });

    long now = System.currentTimeMillis();

    double remaining = Math.max(0.0, (until - now) / 24000.0 / 50.0);

    if (bar != null) {
      double progress =
          Math.max(0.0, Math.min(1.0, remaining / Math.max(1, cfg.getInt("duration", 3))));

      bar.setProgress(progress);

      bar.setTitle(
          remaining >= 1.0
              ? Lang.tr("illness-days", String.format(java.util.Locale.ROOT, "%.1f", remaining))
              : Lang.tr("illness-hours", String.valueOf((int) Math.ceil(remaining * 24.0))));

      bar.setColor(
          progress > 0.66 ? BarColor.BLUE : (progress > 0.33 ? BarColor.PURPLE : BarColor.GREEN));
    }

    double total = Math.max(1, cfg.getInt("duration", 3)) * 24000.0 * 50.0;
    double passed = total - Math.max(0.0, until - now);
    double k = Math.max(0.0, Math.min(1.0, passed / total));

    if (k > 0.5 && player.hasPotionEffect(PotionEffectType.WEAKNESS)) {
      player.removePotionEffect(PotionEffectType.WEAKNESS);
    }

    if (k > 0.8 && player.hasPotionEffect(PotionEffectType.NAUSEA)) {
      player.removePotionEffect(PotionEffectType.NAUSEA);
    }

    if (now < until) {
      return;
    }

    // The cure rolls only while resting in bed (design: "cured only by rest"),
    // with a higher chance at night and half of it when just lying during the day.
    boolean resting = player.isSleeping() || (player.isSneaking() && isOnBed(player));

    if (!resting) {
      return;
    }

    long worldTime = player.getWorld().getTime();

    boolean night = worldTime >= 12541 && worldTime < 23458;

    double chance = cfg.getDouble("cure-chance-night", 0.4) * (night ? 1.0 : 0.5);

    // scale to a per-night probability: rolls arrive every interval-ticks,
    // a night is 24000 ticks, so the per-roll odds are -ln(1-p)/rollsPerNight
    int interval = Math.max(1, plugin.getConfig().getInt("interval-ticks", 20));

    double perRoll = -Math.log(1.0 - Math.min(0.99, chance)) / (24000.0 / interval);

    if (Math.random() < perRoll) {
      cure(player);

      return;
    }

    // the illness has run its course — clean it up even without a cure roll
    illnessUntilMap.remove(id);

    player.getPersistentDataContainer().set(illnessKey, PersistentDataType.LONG, 0L);

    bar.setVisible(false);

    player.sendActionBar(Component.text(Lang.tr("illness-over")));
  }

  /** Instant cure: removes the illness and resets all timers. */
  private void cure(Player player) {
    UUID id = player.getUniqueId();

    for (String entry : illnessEffects) {
      PotionEffectType type = effectType(entry.split(":")[0]);

      if (type != null) {
        player.removePotionEffect(type);
      }
    }

    illnessUntilMap.remove(id);

    player.getPersistentDataContainer().set(illnessKey, PersistentDataType.LONG, 0L);

    outsideTickMap.remove(id);

    BossBar bar = illnessBarMap.remove(id);

    if (bar != null) {
      bar.removeAll();
    }

    player.sendActionBar(Component.text(Lang.tr("illness-cured")));
  }

  private void updateBar(Player player, double value, State state) {
    UUID id = player.getUniqueId();

    if (!plugin.getConfig().getBoolean("bossbar.enabled", true)) {
      BossBar bar = barMap.remove(id);

      if (bar != null) {
        bar.removeAll();
      }

      BossBar illnessBar = illnessBarMap.remove(id);

      if (illnessBar != null) {
        illnessBar.removeAll();
      }

      return;
    }

    BossBar bar =
        barMap.computeIfAbsent(
            id,
            k -> {
              BossBar created =
                  Bukkit.createBossBar("", BarColor.GREEN, barStyle(plugin.getConfig()));

              created.addPlayer(player);

              return created;
            });

    double min = plugin.getConfig().getDouble("bossbar.min", 30.0);
    double max = plugin.getConfig().getDouble("bossbar.max", 43.0);
    double progress = max > min ? (value - min) / (max - min) : 0;

    if (!Double.isFinite(progress)) {
      progress = 0;
    }

    bar.setProgress(Math.max(0, Math.min(1, progress)));
    bar.setTitle(Lang.tr("bossbar-title", one(value)));
    bar.setColor(barColor(plugin.getConfig().getString("colors." + state.id(), "GREEN")));
  }

  private void updateActionBar(
      Player player, double value, State state, Env.Result env, double clothing) {
    if (!plugin.getConfig().getBoolean("thermometer.actionbar", true)
        || !holdingThermometer(player)) {
      return;
    }

    int interval =
        Math.max(10, plugin.getConfig().getInt("thermometer.actionbar-interval-ticks", 40));

    UUID id = player.getUniqueId();

    int clock = actionClockMap.merge(id, tickInterval, Integer::sum);

    if (clock < interval) {
      return;
    }

    actionClockMap.put(id, clock - interval);

    player.sendActionBar(
        Component.text(Lang.tr("actionbar", one(env.temperature()), one(value), label(state))));

    // Clothing is already factored into body temperature; not repeated in the action bar.
  }

  // ---------------------------------------------------------------- boss bar

  private BarStyle barStyle(FileConfiguration cfg) {
    return switch (cfg.getString("bossbar.style", "notched_12")) {
      case "notched_6" -> BarStyle.SEGMENTED_6;
      case "notched_10" -> BarStyle.SEGMENTED_10;
      case "notched_20" -> BarStyle.SEGMENTED_20;
      case "progress" -> BarStyle.SOLID;

      default -> BarStyle.SEGMENTED_12;
    };
  }

  private BarColor barColor(String name) {
    try {
      return BarColor.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException err) {
      return BarColor.WHITE;
    }
  }

  // ---------------------------------------------------------------- seasons

  private void loadSeason() {
    seasonFile = new File(plugin.getDataFolder(), "season.yml");

    YamlConfiguration yml = YamlConfiguration.loadConfiguration(seasonFile);

    season =
        yml.getString("season", plugin.getConfig().getString("start", "WINTER"))
            .toUpperCase(Locale.ROOT);

    elapsedSeasonSeconds = yml.getInt("elapsed-seconds", 0);

    if (yml.isInt("saved-sleeping-percentage")) {
      // A restart happened while a day rest held the rule at 101: restore only our own override.
      World world = overworld();

      Integer current =
          world == null
              ? null
              : world.getGameRuleValue(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE);

      if (world != null && current != null && current == 101) {
        world.setGameRule(
            org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE,
            yml.getInt("saved-sleeping-percentage"));

        yml.set("saved-sleeping-percentage", null);

        try {
          yml.save(seasonFile);
        } catch (Exception err) {
          plugin.getLogger().warning("Failed to save season: " + err.getMessage());
        }
      }
    }
  }

  private void saveSeason() {
    YamlConfiguration yml = new YamlConfiguration();

    yml.set("season", season);
    yml.set("elapsed-seconds", elapsedSeasonSeconds);

    if (savedSleepingPercentage >= 0) {
      yml.set("saved-sleeping-percentage", savedSleepingPercentage);
    }

    try {
      yml.save(seasonFile);
    } catch (Exception err) {
      plugin.getLogger().warning("Failed to save season: " + err.getMessage());
    }
  }

  // ---------------------------------------------------------------- day rest and the night
  //
  // A day-resting player is genuinely sleeping, so vanilla's sleep-status would
  // skip to morning the moment they lie down (and clear the weather). Vanilla
  // gates that skip on the playersSleepingPercentage game rule, so while anyone
  // day-rests we raise it to 101 (unreachable) and take the night skip into our
  // own hands, fully event-driven:
  //  - a day rest schedules one check for the coming nightfall;

  //  - every successful bed enter at night checks whether everyone is in bed.
  // When the last day rest ends, the game rule goes back to its previous value.

  private int savedSleepingPercentage = -1;

  private void clearDayRest(UUID id) {
    if (dayRestMap.remove(id) != null && dayRestMap.isEmpty()) {
      releaseSleepingSkip();
    }
  }

  private void holdSleepingSkip() {
    World world = overworld();

    if (world == null || savedSleepingPercentage >= 0) {
      return;
    }

    Integer current = world.getGameRuleValue(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE);

    savedSleepingPercentage = current == null ? 100 : current;

    world.setGameRule(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE, 101);

    saveSeason();
  }

  private void releaseSleepingSkip() {
    World world = overworld();

    if (savedSleepingPercentage < 0) {
      return;
    }

    Integer current =
        world == null
            ? null
            : world.getGameRuleValue(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE);

    if (world != null && current != null && current == 101) {
      world.setGameRule(org.bukkit.GameRule.PLAYERS_SLEEPING_PERCENTAGE, savedSleepingPercentage);
    }

    savedSleepingPercentage = -1;

    saveSeason();
  }

  private World overworld() {
    for (World candidate : Bukkit.getWorlds()) {
      if (candidate.getEnvironment() == World.Environment.NORMAL) {
        return candidate;
      }
    }

    return null;
  }

  /** Everyone in bed at night (dead and spectators do not block): fast-forward to morning. */
  private void tryNightSkip() {
    World world = overworld();

    if (world == null || dayRestMap.isEmpty()) {
      return;
    }

    long time = world.getTime();

    if (time < 12541 || time >= 23458) {
      return;
    }

    boolean resting = false;

    for (UUID id : dayRestMap.keySet()) {
      Player player = Bukkit.getPlayer(id);

      if (player != null && player.isSleeping()) {
        resting = true;

        break;
      }
    }

    if (!resting) {
      return;
    }

    for (Player player : world.getPlayers()) {
      if (player.isSleeping() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
        continue;
      }

      return; // someone is awake — vanilla rules, no skip
    }

    world.setTime(0);
    // the morning has moved: scheduled wake tasks would fire hours late, wake now
    for (UUID id : java.util.List.copyOf(dayRestMap.keySet())) {
      clearDayRest(id);
      cancelMorningWake(id);
    }

    plugin.getLogger().info("Night skipped: all players are in bed");
  }

  /**
   * Seasonal weather bias, like the real world: winter brings snow more often, summer brings
   * thunderstorms. Rolls on a timer; vanilla decides the duration of whatever it starts. An admin's
   * /weather is respected between rolls.
   */
  private void weatherDirector() {
    World world = overworld();

    if (world == null) {
      return;
    }

    String key = "weather-director.";

    if (!world.hasStorm()) {
      double chance = plugin.getConfig().getDouble(key + "clear-storm-chance." + season, 0.05);

      if (Math.random() < chance) {
        world.setStorm(true);

        plugin.getLogger().info("Weather director: " + season + " brings precipitation");
      }

      return;
    }

    if (!world.isThundering()) {
      double chance = plugin.getConfig().getDouble(key + "storm-thunder-chance." + season, 0.1);

      if (Math.random() < chance) {
        world.setThundering(true);

        plugin.getLogger().info("Weather director: thunderstorm");
      }
    }
  }

  // ---------------------------------------------------------------- blizzard
  //
  // Cosmetic winter storm: snowflakes blown through the air around every player in a
  // snowy biome. Client-side only, so a 2-tick timer with cheap biome/light checks —
  // nothing is stored, nothing is sent as state. The wind comes from one stateless
  // function of world time, the same formula the world core carries.

  /** Degrees. Rotates a full circle every wind.cycle-days days, plus slow gust sway. */
  static double windAngleDeg(World world, double cycleDays) {
    double day = world.getFullTime() / 24000.0;

    return (day / cycleDays) * 360.0 + 25.0 * Math.sin(day * 0.45) + 10.0 * Math.sin(day * 2.1);
  }

  private void blizzardTick() {
    ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("blizzard");

    if (cfg == null || !cfg.getBoolean("enabled", true) || !"WINTER".equals(season)) {
      return;
    }

    List<String> biomes = cfg.getStringList("biomes");

    if (biomes.isEmpty()) {
      return;
    }

    // Clamped: a runaway radius would reach past the loaded chunks every 2 ticks.
    int radius = Math.max(1, Math.min(32, cfg.getInt("radius", 14)));
    int particles = Math.max(1, cfg.getInt("particles-per-tick", 30));

    double speed = Math.max(0.0, cfg.getDouble("wind-speed", 0.45));
    double thunderFactor = Math.max(1.0, cfg.getDouble("thunder-multiplier", 2));

    long now = System.currentTimeMillis();

    for (Player player : Bukkit.getOnlinePlayers()) {
      World world = player.getWorld();

      if (world.getEnvironment() != World.Environment.NORMAL || !world.hasStorm()) {
        continue;
      }

      if (!biomes.contains(world.getBiome(player.getLocation()).getKey().getKey())) {
        continue;
      }

      boolean thundering = world.isThundering();

      double angle = Math.toRadians(windAngleDeg(world, windCycleDays));
      double driftX = Math.cos(angle) * speed;
      double driftZ = Math.sin(angle) * speed;

      Location eye = player.getEyeLocation();

      int count = thundering ? (int) Math.round(particles * thunderFactor) : particles;

      for (int i = 0; i < count; i++) {
        double x = eye.getX() + (Math.random() * 2 - 1) * radius;
        double y = eye.getY() + (Math.random() * 2 - 1) * 3;
        double z = eye.getZ() + (Math.random() * 2 - 1) * radius;

        // No snow indoors: only under an open sky. Light is a byte in the API.
        if (world
                .getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                .getLightFromSky()
            < 15) {
          continue;
        }

        // Count 0 sends one particle whose offsets are its velocity, so the
        // flake actually flies along the wind instead of drifting at random.
        world.spawnParticle(Particle.SNOWFLAKE, x, y, z, 0, driftX, BLIZZARD_FALL, driftZ, 1.0);
      }

      if (thundering && now - lastGust >= 10000L) {
        lastGust = now; // a gust about every ten seconds, along the wind

        for (int i = 0; i < 3 + (int) (Math.random() * 3); i++) {
          world.spawnParticle(
              Particle.GUST, eye.getX(), eye.getY(), eye.getZ(), 0, driftX * 3, 0, driftZ * 3, 1.0);
        }
      }
    }
  }

  private void seasonTick() {
    elapsedSeasonSeconds++;

    long duration = Math.max(1, plugin.getConfig().getInt("duration-minutes", 1440)) * 60L;

    if (elapsedSeasonSeconds < duration) {
      if (elapsedSeasonSeconds % 300 == 0) {
        saveSeason();
      }

      return;
    }

    elapsedSeasonSeconds = 0;

    setSeason(nextSeason());
  }

  private String nextSeason() {
    List<String> order = plugin.getConfig().getStringList("order");

    if (order.isEmpty()) {
      return "WINTER";
    }

    int index = order.indexOf(season);

    return order.get((index + 1) % order.size()).toUpperCase(Locale.ROOT);
  }

  private boolean setSeason(String newSeason) {
    if (plugin.worldBusy()) {
      // the world pass would refuse the season: moving the clock now would desync it
      plugin
          .getLogger()
          .warning("Season NOT changed to " + newSeason + ": a world pass is running");

      return false;
    }

    season = newSeason.toUpperCase(Locale.ROOT);

    elapsedSeasonSeconds = 0;

    saveSeason();

    plugin.getLogger().info("Season changed to " + season);

    if (plugin.getConfig().getBoolean("seasons.call-terrain-pass", true)) {
      Bukkit.dispatchCommand(
          Bukkit.getConsoleSender(), "climate " + season.toLowerCase(Locale.ROOT));
    }

    return true;
  }

  // ---------------------------------------------------------------- thermometer

  private ItemStack thermometer() {
    ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("thermometer");

    Material material =
        Material.matchMaterial(cfg == null ? "CLOCK" : cfg.getString("material", "CLOCK"));

    ItemStack item = new ItemStack(material == null ? Material.CLOCK : material);

    ItemMeta meta = item.getItemMeta();

    meta.displayName(Component.text(Lang.tr("thermometer-name")));

    if (cfg != null && cfg.getBoolean("glint", true)) {
      Enchantment glint = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));

      if (glint != null) {
        meta.addEnchant(glint, 1, true);
      }
    }

    if (cfg != null && cfg.getInt("custom-model-data", 0) > 0) {
      meta.setCustomModelData(cfg.getInt("custom-model-data"));
    }

    meta.getPersistentDataContainer().set(thermometerKey, PersistentDataType.BYTE, (byte) 1);

    item.setItemMeta(meta);

    return item;
  }

  private void updateThermometerRecipe() {
    NamespacedKey recipeKey = new NamespacedKey(plugin, "thermometer_recipe");

    Bukkit.removeRecipe(recipeKey);

    ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("thermometer");

    if (cfg == null || !cfg.getBoolean("recipe-enabled", false)) {
      return;
    }

    ConfigurationSection ingredients = cfg.getConfigurationSection("recipe-ingredients");

    List<String> shape = cfg.getStringList("recipe-shape");

    if (ingredients == null || shape.isEmpty()) {
      return;
    }

    try {
      ShapedRecipe recipe = new ShapedRecipe(recipeKey, thermometer());

      recipe.shape(shape.toArray(String[]::new));

      for (String key : ingredients.getKeys(false)) {
        Material material = Material.matchMaterial(ingredients.getString(key, ""));

        if (key.length() != 1 || material == null) {
          throw new IllegalArgumentException("invalid ingredient " + key);
        }

        recipe.setIngredient(key.charAt(0), material);
      }

      Bukkit.addRecipe(recipe);
    } catch (IllegalArgumentException err) {
      plugin.getLogger().warning("Invalid thermometer recipe: " + err.getMessage());
    }
  }

  private boolean isThermometer(ItemStack item) {
    if (item == null || !item.hasItemMeta()) {
      return false;
    }

    return item.getItemMeta()
        .getPersistentDataContainer()
        .has(thermometerKey, PersistentDataType.BYTE);
  }

  private boolean holdingThermometer(Player player) {
    return isThermometer(player.getInventory().getItemInMainHand())
        || isThermometer(player.getInventory().getItemInOffHand());
  }

  /** Milk does not cure our illness: re-apply the effects immediately. */
  @EventHandler(ignoreCancelled = true)
  public void onMilk(PlayerItemConsumeEvent event) {
    if (event.getItem().getType() != Material.MILK_BUCKET) {
      return;
    }

    if (illnessUntilMap.getOrDefault(event.getPlayer().getUniqueId(), 0L) <= 0) {
      return;
    }

    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              Player player = event.getPlayer();

              applyIllnessEffects(player, Integer.MAX_VALUE);

              player.sendActionBar(Component.text(Lang.tr("illness-milk-no-help")));
            });
  }

  /**
   * During the day only a sick player may use the bed: they lie down and heal. Healthy players get
   * the usual refusal.
   */
  @EventHandler
  public void onBedEnter(org.bukkit.event.player.PlayerBedEnterEvent event) {
    if (event.getBedEnterResult()
        == org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult.OK) {
      // the event fires before the player counts as sleeping: check on the next tick
      Bukkit.getScheduler().runTask(plugin, this::tryNightSkip);

      return;
    }

    if (event.getBedEnterResult()
        != org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW) {
      return; // unsafe beds, other dimensions, other plugins' denials — vanilla rules
    }

    Player player = event.getPlayer();

    UUID id = player.getUniqueId();

    long until = illnessUntilMap.getOrDefault(id, 0L);

    boolean sick = until > 0 || player.hasPotionEffect(PotionEffectType.NAUSEA);
    boolean firstLie = !dayRestMap.containsKey(id);

    event.setUseBed(org.bukkit.event.Event.Result.ALLOW);

    dayRestMap.put(id, event.getBed().getLocation());

    holdSleepingSkip();

    long nightfallDelay = (12541 - player.getWorld().getTime() + 24000) % 24000;

    Bukkit.getScheduler().runTaskLater(plugin, this::tryNightSkip, Math.max(1L, nightfallDelay));

    scheduleMorningWake(id);
    if (firstLie) {
      if (sick) {
        player.sendActionBar(Component.text(Lang.tr("illness-rest-sick")));
      } else {
        player.sendActionBar(Component.text(Lang.tr("illness-rest")));
      }
    }
  }

  /**
   * Sleep is restorative: waking up leaves the body at normal temperature.
   *
   * <p>Day rest semantics: lying in bed commits you to morning — the day does not change while you
   * rest. Vanilla re-checks the bed rule every tick and tries to kick a day sleeper; those kicks
   * are a continuous stream of leave events. The morning wake (after the night skip) is the FIRST
   * leave after a long pause (no kicks fire at night). So: cancel every leave that arrives in a
   * running kick stream, allow the first one after a pause, and at night any leave is a genuine
   * wake — allow it and clear the mark.
   */
  /**
   * Sleep is restorative: waking up leaves the body at normal temperature. Day rest commits to
   * morning: every leave during the day is the vanilla day-kick and is cancelled while the mark
   * lives. The actual wake comes from scheduleMorningWake (the next morning boundary), from the
   * night skip, or from the player pressing the leave-bed button.
   */
  @EventHandler
  public void onBedLeave(org.bukkit.event.player.PlayerBedLeaveEvent event) {
    Player player = event.getPlayer();

    UUID id = player.getUniqueId();

    bodyMap.put(id, plugin.getConfig().getDouble("body-base", 36.6));

    if (!dayRestMap.containsKey(id)) {
      return; // ordinary night sleep — vanilla handles wake-ups
    }

    long time = player.getWorld().getTime();

    boolean night = time >= 12541 && time < 23458;

    if (night || player.isDead() || !isBed(player.getLocation().getBlock().getType())) {
      // night wake, death, or the bed is gone — let the player stand up
      clearDayRest(id);
      cancelMorningWake(id);

      return;
    }

    event.setCancelled(true);
  }

  /**
   * Deterministic morning wake for day resters: one scheduled task per lie-down, landing on the
   * next morning boundary. Robust against /time set — if the time was moved backwards, the task
   * reschedules itself to the new boundary.
   */
  private void scheduleMorningWake(UUID id) {
    World world = overworld();

    if (world == null) {
      return;
    }

    org.bukkit.scheduler.BukkitTask previous = wakeTaskMap.remove(id);

    if (previous != null) {
      previous.cancel();
    }

    long untilMorning = (23458 - world.getTime() + 24000) % 24000;

    wakeTaskMap.put(
        id,
        Bukkit.getScheduler()
            .runTaskLater(plugin, () -> morningWake(id), Math.max(20L, untilMorning)));
  }

  private void cancelMorningWake(UUID id) {
    org.bukkit.scheduler.BukkitTask task = wakeTaskMap.remove(id);

    if (task != null) {
      task.cancel();
    }
  }

  private void morningWake(UUID id) {
    wakeTaskMap.remove(id);

    Player player = Bukkit.getPlayer(id);

    World world = overworld();

    if (player == null || !player.isOnline() || !dayRestMap.containsKey(id) || world == null) {
      return;
    }

    long time = world.getTime();

    if (time >= 23458 || time < 12541) {
      clearDayRest(id); // morning: the next day-kick wakes the player
    } else {
      scheduleMorningWake(id); // time was moved back — the boundary moved too
    }
  }

  // ---------------------------------------------------------------- day rest stand-up button
  //
  // The client never sends a sneak for the leave-bed button — it sends the
  // STOP_SLEEPING player-command packet, which has no Bukkit event. We sniff that
  // packet straight off the player's Netty channel (ProtocolLib-style, self-hosted):
  // pressing the button (or shift, the client maps it to STOP_SLEEPING) clears the
  // rest mark and the very next day-kick wakes the player, ~50 ms later.

  private static final String INJECTOR_NAME = "thermo_dayrest";

  private static Class<?> playerCommandPacket;

  private static java.lang.reflect.Method packetGetAction;

  private void injectDayRestButton(Player player) {
    try {
      Object handle = player.getClass().getMethod("getHandle").invoke(player);
      Object listener = fieldValue(handle, "connection");
      Object network = fieldValue(listener, "connection", "networkManager");

      io.netty.channel.Channel channel = (io.netty.channel.Channel) fieldValue(network, "channel");

      if (channel == null || channel.pipeline().get(INJECTOR_NAME) != null) {
        return;
      }

      channel
          .pipeline()
          .addBefore(
              "packet_handler",
              INJECTOR_NAME,
              new io.netty.channel.ChannelDuplexHandler() {
                @Override
                public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg)
                    throws Exception {
                  try {
                    if (playerCommandPacket == null) {
                      playerCommandPacket =
                          Class.forName(
                              "net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket");

                      packetGetAction = playerCommandPacket.getMethod("getAction");
                    }

                    if (playerCommandPacket.isInstance(msg)) {
                      Object action = packetGetAction.invoke(msg);

                      if ("STOP_SLEEPING".equals(((Enum<?>) action).name())) {
                        // Netty thread: touch the world and the marks on the main thread only
                        Bukkit.getScheduler()
                            .runTask(plugin, () -> clearDayRest(player.getUniqueId()));
                      }
                    }
                  } catch (Throwable ignored) {
                    // sniffing must never break the connection
                  }

                  super.channelRead(ctx, msg);
                }
              });
    } catch (Throwable t) {
      plugin.getLogger().warning("Day rest button sniffing unavailable: " + t);
    }
  }

  private void removeDayRestButton(Player player) {
    try {
      Object handle = player.getClass().getMethod("getHandle").invoke(player);
      Object listener = fieldValue(handle, "connection");
      Object network = fieldValue(listener, "connection", "networkManager");

      io.netty.channel.Channel channel = (io.netty.channel.Channel) fieldValue(network, "channel");

      if (channel != null && channel.pipeline().get(INJECTOR_NAME) != null) {
        channel.pipeline().remove(INJECTOR_NAME);
      }
    } catch (Throwable ignored) {
      // the connection is already gone
    }
  }

  private static Object fieldValue(Object obj, String... names) throws Exception {
    Class<?> type = obj.getClass();

    while (type != null) {
      for (String name : names) {
        try {
          java.lang.reflect.Field field = type.getDeclaredField(name);

          field.setAccessible(true);

          return field.get(obj);
        } catch (NoSuchFieldException ignored) {
        }
      }

      type = type.getSuperclass();
    }

    throw new NoSuchFieldException(String.join("/", names));
  }

  /** Sneak while resting = stand up: it clears the rest mark, the next kick is allowed. */
  @EventHandler
  public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent event) {
    if (event.isSneaking()) {
      clearDayRest(event.getPlayer().getUniqueId());
    }
  }

  @EventHandler
  public void onInteract(PlayerInteractEvent event) {
    if (!isThermometer(event.getItem())) {
      return;
    }

    if (!event.getAction().isRightClick()) {
      return;
    }

    event.setCancelled(true);

    Player player = event.getPlayer();

    Env.Result env = Env.of(player, plugin.getConfig(), season);

    double value =
        bodyMap.getOrDefault(player.getUniqueId(), plugin.getConfig().getDouble("body-base", 36.6));

    State state = stateOf(value);

    double clothing = Env.clothing(player, plugin.getConfig(), player.isInWater());

    player.sendMessage(
        Component.text(Lang.tr("env-line", one(env.temperature()), envState(env.temperature()))));

    player.sendMessage(Component.text(Lang.tr("body-line", one(value), label(state))));

    player.sendMessage(
        Component.text(Lang.tr("clothes-line", (clothing >= 0 ? "+" : "") + one(clothing))));

    player.sendMessage(Component.text(Lang.tr("reason-line", env.reason())));
  }

  private String envState(double env) {
    String key;

    if (env <= -10) {
      key = "env-freezing";
    } else if (env <= 5) {
      key = "env-cold";
    } else if (env <= 25) {
      key = "env-mild";
    } else if (env <= 35) {
      key = "env-warm";
    } else {
      key = "env-hot";
    }

    return Lang.tr("" + key);
  }

  @EventHandler(ignoreCancelled = true)
  public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
    lastMineMap.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    join(event.getPlayer());
  }

  private void join(Player player) {
    UUID id = player.getUniqueId();

    injectDayRestButton(player);

    // Body temperature survives re-logins: it is stored in the player data.
    Double saved = player.getPersistentDataContainer().get(bodyKey, PersistentDataType.DOUBLE);

    bodyMap.put(id, saved != null ? saved : plugin.getConfig().getDouble("body-base", 36.6));

    if (plugin.getConfig().getBoolean("bossbar.enabled", true)) {
      BossBar bar = Bukkit.createBossBar("", BarColor.GREEN, barStyle(plugin.getConfig()));

      bar.addPlayer(player);

      barMap.put(id, bar);
    }

    Long savedIllness =
        player.getPersistentDataContainer().get(illnessKey, PersistentDataType.LONG);

    if (savedIllness != null && savedIllness > System.currentTimeMillis()) {
      illnessUntilMap.put(id, savedIllness);

      BossBar bar =
          illnessBarMap.computeIfAbsent(
              id,
              k -> {
                BossBar created = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);

                created.addPlayer(player);

                return created;
              });

      bar.setTitle(Lang.tr("illness-title"));
      bar.setProgress(1.0);
      bar.setColor(BarColor.BLUE);
      bar.setVisible(true);

      player.sendActionBar(Component.text(Lang.tr("illness-got")));
    } else if (savedIllness != null) {
      player.getPersistentDataContainer().set(illnessKey, PersistentDataType.LONG, 0L);
    }
  }

  @EventHandler
  public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
    UUID id = event.getEntity().getUniqueId();

    clearDayRest(id);
    cancelMorningWake(id);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();

    UUID id = player.getUniqueId();

    removeDayRestButton(player);

    Double value = bodyMap.remove(id);

    if (value != null) {
      player.getPersistentDataContainer().set(bodyKey, PersistentDataType.DOUBLE, value);
    }

    BossBar bar = barMap.remove(id);

    if (bar != null) {
      bar.removeAll();
    }

    BossBar illnessBar = illnessBarMap.remove(id);

    if (illnessBar != null) {
      illnessBar.removeAll();
    }

    bodyMap.remove(id);

    outsideTickMap.remove(id);

    damageClockMap.remove(id);

    lastPositionMap.remove(id);

    lastMineMap.remove(id);

    clearDayRest(id);
  }

  // ---------------------------------------------------------------- commands

  public boolean command(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      sender.sendMessage(Component.text(Lang.tr("usage")));

      return true;
    }

    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "status" -> status(sender, args);
      case "reload" -> {
        if (!sender.hasPermission("climate.admin")) {
          sender.sendMessage(Component.text(Lang.tr("no-permission")));

          return true;
        }

        plugin.reloadConfig();
        plugin.worldsReload();

        Lang.load(plugin);

        loadStates();
        updateThermometerRecipe();

        windCycleDays = Math.max(0.05, plugin.getConfig().getDouble("wind.cycle-days", 3));

        sender.sendMessage(Component.text(Lang.tr("reload-done", season)));
      }

      case "season" -> {
        if (!sender.hasPermission("climate.admin")) {
          sender.sendMessage(Component.text(Lang.tr("no-permission")));

          return true;
        }

        if (args.length < 2) {
          sender.sendMessage(
              Component.text(Lang.tr("season-current", season, String.valueOf(remainMinutes()))));

          return true;
        }

        if (setSeason(args[1])) {
          sender.sendMessage(Component.text(Lang.tr("season-set", season)));
        } else {
          sender.sendMessage(Component.text(Lang.tr("season-busy", season)));
        }
      }

      case "standup", "встать" -> {
        if (sender instanceof Player player) {
          UUID id = player.getUniqueId();

          clearDayRest(id);
          cancelMorningWake(id);

          bodyMap.put(id, plugin.getConfig().getDouble("body-base", 36.6));

          player.sendActionBar(Component.text(Lang.tr("standup-done")));
        }
      }

      case "set" -> {
        if (!sender.hasPermission("climate.admin")
            || !(sender instanceof Player player)
            || args.length < 2) {
          sender.sendMessage(Component.text(Lang.tr("set-usage")));

          return true;
        }

        try {
          double value = Double.parseDouble(args[1]);

          if (!Double.isFinite(value)) {
            sender.sendMessage(Component.text(Lang.tr("not-a-number", args[1])));

            return true;
          }

          bodyMap.put(player.getUniqueId(), value);

          player.sendMessage(
              Component.text(Lang.tr("set-body", one(value), label(stateOf(value)))));

        } catch (NumberFormatException err) {
          sender.sendMessage(Component.text(Lang.tr("not-a-number", args[1])));
        }
      }

      case "thermometer" -> {
        if (!sender.hasPermission("climate.admin")) {
          sender.sendMessage(Component.text(Lang.tr("no-permission")));

          return true;
        }

        Player target =
            args.length > 1
                ? Bukkit.getPlayerExact(args[1])
                : (sender instanceof Player p ? p : null);

        if (target == null) {
          sender.sendMessage(Component.text(Lang.tr("player-not-found")));

          return true;
        }

        target.getInventory().addItem(thermometer());

        sender.sendMessage(Component.text(Lang.tr("thermometer-given", target.getName())));
      }

      default -> sender.sendMessage(Component.text(Lang.tr("unknown-subcommand", args[0])));
    }

    return true;
  }

  private void status(CommandSender sender, String[] args) {
    Player target =
        args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);

    if (target == null) {
      sender.sendMessage(Component.text(Lang.tr("status-only-player")));

      return;
    }

    Env.Result env = Env.of(target, plugin.getConfig(), season);

    double value = bodyMap.getOrDefault(target.getUniqueId(), 36.6);

    State state = stateOf(value);

    double clothing = Env.clothing(target, plugin.getConfig(), target.isInWater());

    sender.sendMessage(
        Component.text(
            Lang.tr(
                "status-line",
                target.getName(),
                one(value),
                state.id(),
                label(state),
                one(env.temperature()),
                env.reason(),
                (clothing >= 0 ? "+" : "") + one(clothing),
                season,
                String.valueOf(remainMinutes()))));
  }

  private long remainMinutes() {
    long duration = Math.max(1, plugin.getConfig().getInt("duration-minutes", 1440)) * 60L;

    return (duration - elapsedSeasonSeconds) / 60;
  }

  public List<String> complete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      return List.of("status", "reload", "season", "set", "thermometer", "standup", "встать")
          .stream()
          .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
          .toList();
    }

    if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
      return List.of("winter", "spring", "summer", "autumn").stream()
          .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
          .toList();
    }

    return List.of();
  }

  private static String one(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }
}
