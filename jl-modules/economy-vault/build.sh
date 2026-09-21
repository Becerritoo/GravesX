#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE="$(cd "$ROOT/../.." && pwd)"
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
OUT="$BUILD/GravesX-EconomyVault.jar"

GRAVES_JAR="$WORKSPACE/target/GravesX-4.9.11.1.jar"
if [[ ! -f "$GRAVES_JAR" ]]; then
  GRAVES_JAR="/srv/minecraft/server-jl/plugins/GravesX-4.9.11.1.jar"
fi

SPIGOT_API="${SPIGOT_API:-$HOME/.m2/repository/org/spigotmc/spigot-api/26.1-R0.1-SNAPSHOT/spigot-api-26.1-R0.1-SNAPSHOT.jar}"
VAULT_API="${VAULT_API:-$HOME/.m2/repository/net/milkbowl/vault/VaultAPI/1.7/VaultAPI-1.7.jar}"

rm -rf "$BUILD"
mkdir -p "$CLASSES"

javac -encoding UTF-8 -source 17 -target 17 \
  -cp "$GRAVES_JAR:$SPIGOT_API:$VAULT_API" \
  -d "$CLASSES" \
  $(find "$ROOT/src/main/java" -name '*.java' | sort)

cp -R "$ROOT/src/main/resources/." "$CLASSES/"
jar cf "$OUT" -C "$CLASSES" .
printf '%s\n' "$OUT"
