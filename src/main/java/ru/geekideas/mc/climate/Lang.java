package ru.geekideas.mc.climate;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Player-facing strings loaded from lang/&lt;locale&gt;.yml with per-key fallback to the bundled
 * English file. A file in the data folder (plugins/Climate/lang/) overrides the copy bundled in the
 * jar, so servers can add their own language.
 */
final class Lang {
  private static YamlConfiguration fallback;
  private static YamlConfiguration active;

  private Lang() {}

  static void load(ru.geekideas.mc.climate.ClimatePlugin plugin) {
    String locale =
        plugin.getConfig().getString("language", "en").toLowerCase(java.util.Locale.ROOT);

    fallback = read(plugin, "en");

    active = locale.equals("en") ? fallback : read(plugin, locale);
  }

  private static YamlConfiguration read(
      ru.geekideas.mc.climate.ClimatePlugin plugin, String locale) {
    File custom = new File(plugin.getDataFolder(), "lang/" + locale + ".yml");

    if (custom.isFile()) {
      return YamlConfiguration.loadConfiguration(custom);
    }

    try (Reader in =
        new InputStreamReader(
            plugin.getResource("lang/" + locale + ".yml"), StandardCharsets.UTF_8)) {
      return YamlConfiguration.loadConfiguration(in);
    } catch (Exception err) {
      plugin.getLogger().warning("Language '" + locale + "' not found, using English");

      return fallback != null ? fallback : new YamlConfiguration();
    }
  }

  /** A biome key (plains) as the player should read it; raw key when untranslated. */
  static String trBiome(String key) {
    String translated = tr("biome." + key);

    return translated.equals("biome." + key) ? key.replace('_', ' ') : translated;
  }

  /** A block type name (GRASS_BLOCK) as the player should read it. */
  static String trBlock(String name) {
    String translated = tr("block." + name.toLowerCase(java.util.Locale.ROOT));

    return translated.equals("block." + name.toLowerCase(java.util.Locale.ROOT))
        ? name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
        : translated;
  }

  /** Looks up key in the active locale, falls back to English, then to the key itself. */
  static String tr(String key, String... params) {
    String text = active.getString(key);

    if (text == null) {
      text = fallback.getString(key);
    }

    if (text == null) {
      return key;
    }

    for (int i = 0; i < params.length; i++) {
      text = text.replace("{" + i + "}", params[i]);
    }

    return text;
  }
}
