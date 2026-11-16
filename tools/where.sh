#!/usr/bin/env bash
# where.sh X Y Z — prints the biomes from the list that stand at the point (live world).
# The list is every biome that occurs in this world plus its seasonal variants.
set -euo pipefail

cd ~/repos/minecraft-server

PASS=$(grep -h '^RCON_PASSWORD=' env/default.env env/production.env | tail -1 | cut -d= -f2)

rcon() {
  docker exec -i minecraft_server ./mcrcon-x86_64 -p "${PASS}" "$1" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tr -d '\r'
}

X="$1"
Y="$2"
Z="$3"

CANDIDATES="plains sunflower_plains flower_forest forest birch_forest dark_forest taiga
old_growth_pine_taiga old_growth_spruce_taiga snowy_taiga snowy_plains snowy_beach snowy_slopes
meadow windswept_hills windswept_gravelly_hills windswept_forest windswept_savanna
swamp mangrove_swamp jungle bamboo_jungle sparse_jungle cherry_grove savanna savanna_plateau
desert badlands eroded_badlands wooded_badlands beach stony_shore stony_peaks jagged_peaks
frozen_peaks ice_spikes ocean deep_ocean warm_ocean lukewarm_ocean cold_ocean deep_cold_ocean
deep_lukewarm_ocean deep_frozen_ocean frozen_ocean river frozen_river mushroom_fields
lush_caves dripstone_caves deep_dark grove pale_garden"

rcon "forceload add ${X} ${Z}" >/dev/null

for BIOME in ${CANDIDATES}; do
  OUT=$(rcon "execute if biome ${X} ${Y} ${Z} minecraft:${BIOME}")

  case "${OUT}" in
    *"Test passed"*)
      printf '%s\n' "${BIOME}"
      ;;
  esac
done

rcon "forceload remove ${X} ${Z}" >/dev/null
