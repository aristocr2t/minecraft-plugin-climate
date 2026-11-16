#!/usr/bin/env bash
# Formats all Java sources with google-java-format (2-space indent, 100 cols).
# Run ./build.sh afterwards — formatting must not change behavior.
set -euo pipefail

cd "$(dirname "$0")"

GJF="${GJF:-/tmp/google-java-format.jar}"
GJF_URL="https://repo1.maven.org/maven2/com/google/googlejavaformat/google-java-format/1.26.0/google-java-format-1.26.0-all-deps.jar"

if [ ! -f "${GJF}" ]; then
  printf 'Downloading google-java-format...\n' >&2

  curl -sL -o "${GJF}" "${GJF_URL}"
fi

mapfile -t SOURCES < <(find src -name "*.java")

docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/app -w /app -v "${GJF}":/gjf.jar eclipse-temurin:21-jdk \
  java -jar /gjf.jar --replace "${SOURCES[@]}"
