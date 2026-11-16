#!/usr/bin/env bash
# Builds climate-1.0.0.jar in Docker (JDK 25 — for api-version 26.3).
# lib/*.jar is the compile classpath; only the SQLite driver is unpacked into the jar.
set -euo pipefail

cd "$(dirname "$0")"

JAR="climate-1.0.0.jar"

mkdir -p out

docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/app -w /app eclipse-temurin:25-jdk sh -c "
  set -e

  rm -rf out ${JAR}
  mkdir -p out

  CP=\$(ls lib/*.jar | tr '\n' ':')

  javac -encoding UTF-8 --release 25 -Xlint:-deprecation -cp \"\$CP\" -d out \$(find src -name '*.java')

  cd out
  jar --extract --file ../lib/sqlite-jdbc.jar
  rm -f module-info.class
  cd ..

  jar --create --file ${JAR} -C out . -C . plugin.yml -C . config.yml -C . lang -C . README.md -C . AGENTS.md
"

ls -l "${JAR}"
