package ru.geekideas.mc.climate;

import java.io.File;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Seasonal biomes: a map of original biomes in SQLite + operations over it
 * (spring/summer/autumn/winter, restore). Schema and commands are in README.md.
 */
public final class Terrain {
  private final ru.geekideas.mc.climate.ClimatePlugin plugin;

  public Terrain(ru.geekideas.mc.climate.ClimatePlugin plugin) {
    this.plugin = plugin;
  }

  /** The hosting JavaPlugin: scheduler, server and metadata live behind it. */
  public ru.geekideas.mc.climate.ClimatePlugin host() {
    return plugin;
  }

  public java.util.logging.Logger getLogger() {
    return plugin.getLogger();
  }

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss");

  private static final List<String> SUBCOMMANDS =
      List.of("scan", "spring", "summer", "autumn", "winter", "restore", "status", "stop");

  private final Map<String, String> spring = new LinkedHashMap<>();
  private final Map<String, String> summer = new LinkedHashMap<>();
  private final Map<String, String> autumn = new LinkedHashMap<>();
  private final Map<String, String> winter = new LinkedHashMap<>();
  private final Map<String, Biome> biomes = new HashMap<>();
  private Store store;
  private String storeError;
  private Pass pass;
  private String lastOp;
  private String scanState;
  private int chunksPerTick;
  private int millisPerTick;
  private int commitEvery;
  boolean thawEnabled;
  int thawPerChunk;
  boolean grassAutumn;
  boolean grassWinter;
  boolean grassSpring;
  double grassWinterChance;
  double grassSpringChance;
  boolean farWeatherEnabled;
  int farChunksPerTick;
  int farMillisPerTick;
  double farAttemptsPerTick;
  int farMaxAttemptCount;
  int farMaxSnowLayers;
  double grassRegrowChance;
  double grassRegrowWildChance;
  double grassDieChance;
  int snowKillLayers;
  double grassGrowChance;
  double grassSpreadChance;
  double grassSpringTurfChance;
  boolean newChunkSeasoning;
  int newChunkColumnCount;
  double newChunkSnowSpread;
  boolean frostedIce;
  boolean frostedIceNearMelt;
  double windCycleDays;
  Set<String> farSnowBiomes = Set.of();
  Set<String> aridBiomeSet = Set.of();

  // Running map-wide estimate of what the season leaves behind, fed by far weather:
  // net snow layers over the columns that ever got snow, and the grass that snow
  // has already buried (grass block → dirt).
  long snowColumns;
  long snowLayers;
  long grassColumns;
  long grassBuried;

  private FarWeather weatherPass;

  private boolean weatherFailing;

  private boolean summerSeason;

  private int idlePassCount;

  private int logEvery;

  private int minY;

  private int maxY;
  private int scanMaxRadius;
  private boolean scanOnEnable;

  public void start() {
    plugin.saveDefaultConfig();

    Lang.load(plugin);

    lastOp = Lang.tr("last-op-none");
    scanState = Lang.tr("scan-never");

    loadConfig();

    summerSeason = "SUMMER".equals(thermoSeason());

    File db = new File(plugin.getDataFolder(), "biomes.db");

    try {
      store = new Store(db);

      store.open();

      String saved = store.meta("scan_ring");

      if (saved != null) {
        scanState = Lang.tr("scan-paused", saved);
      }
    } catch (SQLException | RuntimeException err) {
      storeError = err.getMessage();

      plugin.getLogger().severe("Map unavailable: " + storeError);

      err.printStackTrace();
      if (store != null) {
        store.close();
      }

      store = null;
    }

    if (store != null && scanOnEnable) {
      Bukkit.getScheduler().runTaskLater(plugin, this::autoScan, 100L);
    }

    if (store != null) {
      Bukkit.getScheduler().runTaskTimer(plugin, this::weatherWatch, 100L, 20L);
    }
  }

  private void loadConfig() {
    chunksPerTick = Math.max(1, plugin.getConfig().getInt("chunks-per-tick", 16));
    millisPerTick = Math.max(1, plugin.getConfig().getInt("max-millis-per-tick", 20));
    commitEvery = Math.max(1, plugin.getConfig().getInt("commit-every-chunks", 32));
    thawEnabled = plugin.getConfig().getBoolean("thaw.enabled", true);
    thawPerChunk = Math.max(0, plugin.getConfig().getInt("thaw.per-chunk", 48));

    grassAutumn = plugin.getConfig().getBoolean("thaw.grass.autumn", true);
    grassWinter = plugin.getConfig().getBoolean("thaw.grass.winter", true);
    grassSpring = plugin.getConfig().getBoolean("thaw.grass.spring", true);
    grassWinterChance =
        Math.max(0.0, Math.min(1.0, plugin.getConfig().getDouble("thaw.grass.winter-chance", 0.8)));

    grassSpringChance =
        Math.max(0.0, Math.min(1.0, plugin.getConfig().getDouble("thaw.grass.spring-chance", 0.3)));

    farWeatherEnabled = plugin.getConfig().getBoolean("far-weather.enabled", true);

    farChunksPerTick = Math.max(1, plugin.getConfig().getInt("far-weather.chunks-per-tick", 4));

    farMillisPerTick =
        Math.max(1, plugin.getConfig().getInt("far-weather.max-millis-per-tick", 25));

    farAttemptsPerTick =
        Math.max(0.0, plugin.getConfig().getDouble("far-weather.attempts-per-tick", 1.0 / 16.0));

    farMaxAttemptCount =
        Math.max(1, plugin.getConfig().getInt("far-weather.max-attempts-per-visit", 400));

    farMaxSnowLayers =
        Math.max(1, Math.min(8, plugin.getConfig().getInt("far-weather.max-snow-layers", 7)));

    grassRegrowChance =
        Math.max(
            0.0,
            Math.min(1.0, plugin.getConfig().getDouble("far-weather.grass-regrow-chance", 0.3)));

    grassRegrowWildChance =
        Math.max(
            0.0,
            Math.min(
                1.0, plugin.getConfig().getDouble("far-weather.grass-regrow-wild-chance", 0.03)));

    grassDieChance =
        Math.max(
            0.0, Math.min(1.0, plugin.getConfig().getDouble("far-weather.grass-die-chance", 0.06)));

    snowKillLayers =
        Math.max(1, Math.min(8, plugin.getConfig().getInt("far-weather.snow-kill-layers", 2)));

    grassGrowChance =
        Math.max(
            0.0, Math.min(1.0, plugin.getConfig().getDouble("far-weather.grass-grow-chance", 0.1)));

    grassSpreadChance =
        Math.max(
            0.0,
            Math.min(1.0, plugin.getConfig().getDouble("far-weather.grass-spread-chance", 0.06)));

    grassSpringTurfChance =
        Math.max(
            0.0, Math.min(1.0, plugin.getConfig().getDouble("thaw.grass.spring-turf-chance", 0.5)));

    newChunkSeasoning = plugin.getConfig().getBoolean("far-weather.new-chunk-seasoning", true);

    newChunkColumnCount =
        Math.max(0, Math.min(256, plugin.getConfig().getInt("far-weather.new-chunk-columns", 48)));

    newChunkSnowSpread =
        Math.max(0.0, plugin.getConfig().getDouble("far-weather.new-chunk-snow-spread", 1.0));

    frostedIce = plugin.getConfig().getBoolean("far-weather.frosted-ice.enabled", true);
    frostedIceNearMelt = plugin.getConfig().getBoolean("far-weather.frosted-ice.near-melt", true);
    windCycleDays = Math.max(0.05, plugin.getConfig().getDouble("wind.cycle-days", 3));

    farSnowBiomes = Set.copyOf(plugin.getConfig().getStringList("far-weather.snow-biomes"));
    aridBiomeSet = Set.copyOf(plugin.getConfig().getStringList("far-weather.arid-biomes"));

    logEvery = Math.max(1, plugin.getConfig().getInt("log-every-chunks", 200));

    minY = plugin.getConfig().getInt("min-y", 40);
    maxY = plugin.getConfig().getInt("max-y", 320);
    scanMaxRadius = plugin.getConfig().getInt("scan-max-radius", 0);
    scanOnEnable = plugin.getConfig().getBoolean("scan-on-enable", true);
    if (minY < 0 || maxY <= minY) {
      plugin.getLogger().warning("min-y/max-y invalid (" + minY + ".." + maxY + "), using 40..320");

      minY = 40;

      maxY = 320;
    }

    spring.clear();

    summer.clear();

    autumn.clear();

    winter.clear();

    readMap("spring-map", spring);
    readMap("summer-map", summer);
    readMap("autumn-map", autumn);
    readMap("winter-map", winter);

    plugin
        .getLogger()
        .info(
            "mapping: spring "
                + spring.size()
                + ", summer "
                + summer.size()
                + ", autumn "
                + autumn.size()
                + ", winter "
                + winter.size()
                + "; heights "
                + minY
                + ".."
                + maxY
                + " ("
                + layers()
                + " layers of 4 blocks, "
                + cellsPerChunk()
                + " cells per chunk)");
  }

  public void reload() {
    loadConfig();

    Lang.load(plugin);

    idlePassCount = 0;

    if (weatherPass != null) {
      weatherPass.reloadGoals();

      if (!farWeatherEnabled) {
        weatherPass.stop();

        weatherPass = null;
      }
    }
  }

  boolean isSummer() {
    return summerSeason;
  }

  public void stop() {
    closing = true;

    if (pass != null) {
      pass.stop();
    }

    if (weatherPass != null) {
      weatherPass.stop();
    }

    maybeCloseStore(); // chunk callbacks may still be in flight: they finish first
  }

  /** The store survives until the last pass callback has returned. */
  private void maybeCloseStore() {
    if (!closing || store == null) {
      return;
    }

    boolean busy =
        (pass != null && !(pass.isFinished() && pass.inFlightCount == 0))
            || (weatherPass != null
                && !(weatherPass.isFinished() && weatherPass.inFlightCount == 0));

    if (!busy) {
      store.close();

      store = null;
    }
  }

  private void autoScan() {
    if (pass == null && store != null && store.meta("season_applied") == null) {
      plugin.getLogger().info("scan-on-enable: resuming map build from world center");

      startScan(scanMaxRadius);
    } else if (pass == null && store != null) {
      plugin
          .getLogger()
          .info("scan-on-enable: skipped — a season is applied; run /climate restore first");
    }
  }

  /**
   * Keeps the far-weather pass running: snow and freezing during precipitation, gradual melting
   * after winter. The pass sleeps after three full passes that changed nothing, and wakes on
   * precipitation or any scan/season pass.
   */
  private void weatherWatch() {
    summerSeason = "SUMMER".equals(thermoSeason());

    if (!farWeatherEnabled) {
      return;
    }

    World world = null;

    for (World candidate : Bukkit.getWorlds()) {
      if (candidate.getEnvironment() == World.Environment.NORMAL) {
        world = candidate;

        break;
      }
    }

    if (world == null || store == null) {
      return;
    }

    if (weatherPass != null && weatherPass.isFinished()) {
      idlePassCount = weatherPass.weatherChanges() > 0 ? 0 : idlePassCount + 1;

      weatherPass = null;
    }

    if (world.hasStorm()) {
      idlePassCount = 0; // precipitation may bring new snow
    }

    if (idlePassCount >= 3) {
      return; // three idle passes in a row: nothing to do
    }

    if (weatherPass == null) {
      try {
        weatherPass = new FarWeather(this, world, store);

        weatherPass.start();

        weatherFailing = false;
      } catch (RuntimeException err) {
        weatherPass = null; // the map is unreadable: report once, keep the server going

        if (!weatherFailing) {
          weatherFailing = true;

          plugin.getLogger().severe("far weather cannot start: " + err);
        }
      }
    }
  }

  // ---------- config ----------

  /** "Original biome → season biome" table. An empty section means the biome never changes. */
  private void readMap(String path, Map<String, String> target) {
    ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);

    if (section == null) {
      plugin.getLogger().warning("config: no section " + path);

      return;
    }

    for (String key : section.getKeys(false)) {
      String from = normalize(key);
      String to = normalize(section.getString(key, ""));

      if (biome(from) == null) {
        plugin.getLogger().warning(path + ": unknown biome " + key);

        continue;
      }

      if (biome(to) == null) {
        plugin
            .getLogger()
            .warning(path + ": unknown target " + section.getString(key) + " for " + key);

        continue;
      }

      target.put(from, to);
    }
  }

  private static String normalize(String name) {
    if (name == null) {
      return "";
    }

    String trimmed = name.trim().toLowerCase(Locale.ROOT);

    return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
  }

  Biome biome(String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }

    Biome cached = biomes.get(name);

    if (cached != null) {
      return cached;
    }

    try {
      Biome biome = Registry.BIOME.get(NamespacedKey.minecraft(name));

      if (biome != null) {
        biomes.put(name, biome);
      }

      return biome;
    } catch (IllegalArgumentException err) {
      return null;
    }
  }

  int layers() {
    return (maxY >> 2) - (minY >> 2) + 1;
  }

  int minQuartY() {
    return minY >> 2;
  }

  int maxQuartY() {
    return maxY >> 2;
  }

  int cellsPerChunk() {
    return layers() * 16;
  }

  /**
   * What to set a cell with the given original biome to in this season. Keys are all biomes that
   * take part in the cycle at all (union of the tables). Spring and restore return the original
   * biome; a biome outside the tables never changes.
   */
  Map<String, String> goals(Season season) {
    Set<String> changeable = new HashSet<>(spring.keySet());

    changeable.addAll(summer.keySet());
    changeable.addAll(autumn.keySet());
    changeable.addAll(winter.keySet());
    Map<String, String> goals = new HashMap<>();

    for (String name : changeable) {
      String goal;

      if (season == Season.SPRING || season == Season.RESTORE) {
        goal = name;
      } else {
        goal =
            switch (season) {
              case SUMMER -> summer.get(name);
              case AUTUMN -> autumn.get(name);
              case WINTER -> winter.get(name);

              default -> null;
            };
      }

      if (goal == null || biome(goal) == null) {
        continue;
      }

      goals.put(name, goal);
    }

    return goals;
  }

  // ---------- map-wide estimates (new-chunk seasoning) ----------

  /** A snow layer landed on a column; the first one also counted the column. */
  void snowLanded(boolean freshColumn) {
    if (freshColumn) {
      snowColumns++;
    }

    snowLayers++;
  }

  /** A whole layer stack placed on a fresh chunk column in one go. */
  void snowPlaced(int layers) {
    snowColumns++;

    snowLayers += Math.max(1, layers);
  }

  /** A snow layer melted away: the estimate is net, so melted snow stops counting. */
  void snowMelted() {
    if (snowLayers > 0) {
      snowLayers--;
    }
  }

  /** Grass column seen under snow that can bury it (grass block under snow-kill-layers). */
  void grassUnderSnow(boolean buried) {
    grassColumns++;

    if (buried) {
      grassBuried++;
    }
  }

  /**
   * Mean snow depth over the map: what a fresh chunk would have got if the season had run there.
   */
  double averageSnowDepth() {
    return snowColumns == 0 ? 2.0 : Math.min(farMaxSnowLayers, (double) snowLayers / snowColumns);
  }

  /** Share of the map's grass that winter has already turned to dirt. */
  double buriedGrassShare() {
    return grassColumns == 0 ? 0.5 : (double) grassBuried / grassColumns;
  }

  World overworld(CommandSender sender) {
    for (World world : Bukkit.getWorlds()) {
      if (world.getEnvironment() == World.Environment.NORMAL) {
        return world;
      }
    }

    tell(sender, "no-overworld");

    return null;
  }

  // ---------- commands ----------

  /** Handles one /climate invocation that belongs to the world half. */
  public boolean command(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("climate.admin")) {
      tell(sender, "no-permission");

      return true;
    }

    if (store == null) {
      tell(sender, "map-unavailable", storeError == null ? "" : storeError);

      return true;
    }

    String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

    switch (sub) {
      case "status" -> status(sender);
      case "probe" -> probe(sender, args);
      case "stop" -> {
        if (pass == null) {
          tell(sender, "nothing-running");
        } else {
          pass.stop();

          pass = null;

          tell(sender, "pass-stopped");
        }
      }

      case "scan" -> scanCommand(sender, args);
      case "spring", "summer", "autumn", "winter", "restore" ->
          applyCommand(sender, Season.parse(sub), args);
      default -> tell(sender, "usage");
    }

    return true;
  }

  private void scanCommand(CommandSender sender, String[] args) {
    String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";

    switch (action) {
      case "start" -> {
        if (pass != null && !pass.isFinished()) {
          tell(sender, "already-running", pass.label(), pass.progress());

          return;
        }

        int radius = args.length >= 3 ? parseInt(sender, args[2]) : scanMaxRadius;

        if (radius < 0) {
          return;
        }

        startScan(radius);

        String limit =
            radius <= 0 ? Lang.tr("no-limit") : Lang.tr("blocks", String.valueOf(radius));

        tell(sender, "scan-started", limit);
      }

      case "stop" -> {
        if (pass == null) {
          tell(sender, "scan-not-running");
        } else {
          pass.stop();

          pass = null;

          tell(sender, "scan-stopped");
        }
      }

      default -> status(sender);
    }
  }

  /** Seasonal random tick speed: 0 in winter (snow only accumulates), fast melting in summer. */
  private void applyRandomTickSpeed(World world, Season season) {
    String state =
        season == Season.RESTORE
            ? "spring" // restore returns the world to its original (spring-like) state
            : season.state.toLowerCase(java.util.Locale.ROOT);

    String key = "random-tick-speed." + state;

    if (!plugin.getConfig().isInt(key)) {
      return;
    }

    int value = plugin.getConfig().getInt(key);

    try {
      org.bukkit.GameRule<Integer> rule = org.bukkit.GameRule.RANDOM_TICK_SPEED;

      Integer before = world.getGameRuleValue(rule);

      world.setGameRule(rule, value);

      plugin
          .getLogger()
          .info(
              "randomTickSpeed: "
                  + before
                  + " -> "
                  + world.getGameRuleValue(rule)
                  + " ("
                  + state
                  + ")");

    } catch (Exception err) {
      plugin.getLogger().warning("failed to set randomTickSpeed: " + err);
    }
  }

  private void applyCommand(CommandSender sender, Season season, String[] args) {
    if (season == null) {
      tell(sender, "unknown-season");

      return;
    }

    if (pass instanceof Scanner) {
      // scanning writes original_biome; applying seasons concurrently would
      // bake the seasonal biome in as the "original" forever
      tell(sender, "already-running", pass.label(), pass.progress());

      return;
    }

    if (pass != null && !pass.isFinished()) {
      tell(sender, "already-running", pass.label(), pass.progress());

      return;
    }

    int radius = args.length >= 2 ? parseInt(sender, args[1]) : 0;

    if (radius < 0) {
      return;
    }

    World world = overworld(sender);

    if (world == null) {
      return;
    }

    applyRandomTickSpeed(world, season);

    Applier applier =
        new Applier(this, world, store, season, chunksPerTick, millisPerTick, radius, commitEvery);

    if (applier.planned() == 0) {
      tell(sender, "nothing-to-change", season.state);

      return;
    }

    pass = applier;

    applier.start();

    String radiusPart =
        radius > 0 ? Lang.tr("radius-part", String.valueOf(radius)) : Lang.tr("whole-map");

    tell(sender, "apply-started", season.state, String.valueOf(applier.planned()), radiusPart);
  }

  private int parseInt(CommandSender sender, String text) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException err) {
      tell(sender, "radius-not-number", text);

      return -1;
    }
  }

  private void startScan(int radiusBlocks) {
    World world = overworld(Bukkit.getConsoleSender());

    if (world == null) {
      return;
    }

    Scanner scanner =
        new Scanner(
            this, world, store, chunksPerTick, millisPerTick, radiusBlocks, commitEvery, logEvery);

    pass = scanner;

    scanner.start();
  }

  void passFinished(Pass finishedPass, String summary) {
    if (!finishedPass.countsAsOperation()) {
      return; // background pass: nothing to show in /climate status
    }

    idlePassCount = 0; // a scan or season pass may have changed the world
    lastOp = finishedPass.label() + ": " + summary + " (" + LocalDateTime.now().format(TIME) + ")";

    if (finishedPass == pass) {
      pass = null;
    }

    if (finishedPass == weatherPass) {
      weatherPass = null;
    }

    maybeCloseStore();

    if ("scan".equals(finishedPass.label())) {
      scanState = Lang.tr("scan-finished", lastOp);
    }
  }

  /** One column of the world: the surface block, what stands on it, the biome. */
  private void probe(CommandSender sender, String[] args) {
    if (args.length < 3) {
      tell(sender, "probe-usage");

      return;
    }

    int x;
    int z;

    try {
      x = Integer.parseInt(args[1]);
      z = Integer.parseInt(args[2]);
    } catch (NumberFormatException err) {
      tell(sender, "probe-usage");

      return;
    }

    World world =
        Bukkit.getWorlds().stream()
            .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
            .findFirst()
            .orElse(null);

    if (world == null) {
      tell(sender, "no-overworld");

      return;
    }

    int y = world.getHighestBlockYAt(x, z);

    Block top = world.getBlockAt(x, y, z);
    Block above = top.getRelative(org.bukkit.block.BlockFace.UP);

    tell(
        sender,
        "probe-result",
        String.valueOf(x),
        String.valueOf(z),
        Lang.trBlock(top.getType().name()) + "@" + y,
        Lang.trBlock(above.getType().name()),
        Lang.trBiome(top.getBiome().getKey().getKey()));
  }

  private static String stateName(String state) {
    return switch (state) {
      case "spring" -> Lang.tr("season-spring");
      case "summer" -> Lang.tr("season-summer");
      case "autumn" -> Lang.tr("season-autumn");
      case "winter" -> Lang.tr("season-winter");
      case "original" -> Lang.tr("state-original");

      default -> state;
    };
  }

  // ---------- status ----------

  private void status(CommandSender sender) {
    String seasonLine = thermoSeasonLine();

    if (seasonLine != null) {
      tell(sender, "status-season", seasonLine);
    }

    World world = overworld(sender);

    Map<String, long[]> totals = world == null ? Map.of() : store.totals(world.getName());

    long[] sum = new long[7];

    List<String> byState = new ArrayList<>();

    for (Map.Entry<String, long[]> entry : totals.entrySet()) {
      byState.add(stateName(entry.getKey()) + ": " + fmt(entry.getValue()[0]));

      for (int i = 0; i < sum.length; i++) {
        sum[i] += entry.getValue()[i];
      }
    }

    tell(
        sender,
        "status-map",
        world == null ? "?" : world.getName(),
        fmt(sum[0]),
        fmt(sum[1] + sum[2] + sum[3] + sum[4] + sum[5]),
        String.format(Locale.ROOT, "%.0f", store.fileSize() / 1048576.0));

    tell(
        sender,
        "status-cells",
        fmt(sum[1]),
        fmt(sum[2]),
        fmt(sum[3]),
        fmt(sum[4]),
        fmt(sum[5]),
        fmt(sum[6]));

    tell(sender, "status-chunks", String.join(", ", byState));

    String scanRing = store.meta("scan_ring");

    boolean complete = "1".equals(store.meta("scan_complete"));

    String scanLine =
        complete
            ? Lang.tr("status-scan-done")
            : scanState + (scanRing == null ? "" : Lang.tr("scan-ring", scanRing));

    tell(sender, "status-scan", scanLine);

    if (pass != null && !pass.isFinished()) {
      tell(sender, "status-running", pass.label(), pass.progress());
    }

    tell(sender, "status-last", lastOp);
  }

  /** A scan or season pass is running: the map and the applied season are about to change. */
  public boolean busy() {
    return pass != null && !pass.isFinished();
  }

  /** Current season of the Thermo clock (WINTER/SPRING/SUMMER/AUTUMN), "" when unknown. */
  private boolean closing;

  private String thermoSeason() {
    File seasonFile = new File(plugin.getDataFolder(), "season.yml");

    if (!seasonFile.isFile()) {
      return "";
    }

    String season =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(seasonFile)
            .getString("season", "");

    return season == null ? "" : season.toUpperCase(Locale.ROOT);
  }

  /**
   * The season clock lives in the body core (it calls /climate on change). Reads its season.yml and
   * config to answer "which season now and how long until the switch" — null when Thermo is not
   * installed.
   */
  private String thermoSeasonLine() {
    File thermoDir = plugin.getDataFolder();
    File seasonFile = new File(thermoDir, "season.yml");

    if (!seasonFile.isFile()) {
      return null;
    }

    org.bukkit.configuration.file.YamlConfiguration yml =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(seasonFile);

    String season = yml.getString("season", "WINTER").toUpperCase(Locale.ROOT);

    int elapsed = yml.getInt("elapsed-seconds", 0);

    org.bukkit.configuration.file.YamlConfiguration thermoCfg =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            new File(thermoDir, "config.yml"));

    long durationSeconds = Math.max(1, thermoCfg.getInt("duration-minutes", 1440)) * 60L;
    long remainMinutes = Math.max(0, (durationSeconds - elapsed) / 60);

    String remain =
        remainMinutes >= 120
            ? Lang.tr(
                "remain-hours",
                String.valueOf(remainMinutes / 60),
                String.valueOf(remainMinutes % 60))
            : Lang.tr("remain-minutes", String.valueOf(remainMinutes));

    return Lang.tr("season-" + season.toLowerCase(Locale.ROOT), season) + " (" + remain + ")";
  }

  /** Long numbers with thousands separated by spaces: 16 129 222. */
  private static String fmt(long n) {
    return String.format(Locale.ROOT, "%,d", n).replace(',', ' ');
  }

  /** Sends a localized "[Terrain] ..." message. The first argument is a lang key. */
  private static void tell(CommandSender sender, String key, String... params) {
    sender.sendMessage(Component.text("[Climate] " + Lang.tr("" + key, params)));
  }

  public List<String> complete(CommandSender sender, Command command, String alias, String[] args) {
    if (!sender.hasPermission("climate.admin")) {
      return List.of();
    }

    if (args.length == 1) {
      return filter(SUBCOMMANDS, args[0]);
    }

    if (args.length == 2 && args[0].equalsIgnoreCase("scan")) {
      return filter(List.of("start", "stop", "status"), args[1]);
    }

    return List.of();
  }

  private static List<String> filter(List<String> options, String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);

    List<String> matches = new ArrayList<>();

    for (String option : options) {
      if (option.startsWith(lower)) {
        matches.add(option);
      }
    }

    return matches;
  }
}
