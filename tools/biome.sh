#!/usr/bin/env bash
# biome.sh X Y Z BIOME — prints PASS if the given biome stands at the point; force-loads the chunk for a moment.
# The check uses the vanilla /execute if biome, i.e. the live world, not the plugin map.
set -euo pipefail

cd ~/repos/minecraft-server

PASS=$(grep -h '^RCON_PASSWORD=' env/default.env env/production.env | tail -1 | cut -d= -f2)

rcon() {
  docker exec -i minecraft_server ./mcrcon-x86_64 -p "${PASS}" "$1" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tr -d '\r'
}

X="$1"
Y="$2"
Z="$3"
BIOME="$4"

rcon "forceload add ${X} ${Z}" >/dev/null

OUT=$(rcon "execute if biome ${X} ${Y} ${Z} minecraft:${BIOME}")

rcon "forceload remove ${X} ${Z}" >/dev/null

case "${OUT}" in
  *"Test passed"*)
    printf 'PASS\n'
    ;;

  *)
    printf 'FAIL\n'
    ;;
esac
