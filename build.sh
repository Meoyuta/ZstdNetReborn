#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$ROOT/target"
LOG="$ROOT/build.log"
EXIT_CODE=0

main() {
    local version
    version="$(sed -n 's/^mod_version=//p' "$ROOT/gradle.properties" | head -n 1)"
    if [[ -z "$version" ]]; then
        echo "Could not read mod_version from gradle.properties."
        return 1
    fi

    mkdir -p "$TARGET" || return 1
    find "$TARGET" -type f -name '*.jar' -delete || return 1

    "$ROOT/gradlew" --no-daemon :neoforge:clean :neoforge:build \
        -Pminecraft_version=1.21.1 \
        -Pjava_version=21 \
        -Parchitectury_api_version=13.0.11 \
        -Pneoforge_version=21.1.223 \
        -Pneoforge_fml_loader_version=4.0.42 \
        -Pneoforge_variant=1_21_1 \
        -Pclient_loom_enabled=true \
        -Pfabric_loom_enabled=false \
        -Pnamed_client_jar_enabled=false || return 1

    local jar="$ROOT/neoforge/build/libs/neoforge-$version.jar"
    local output="$TARGET/ZstdNet-1.21.1-neoforge-server-client-$version.jar"
    if [[ ! -f "$jar" ]]; then
        echo "Expected NeoForge jar was not found: $jar"
        return 1
    fi
    cp -f "$jar" "$output" || return 1
    echo "Built NeoForge 1.21.1 server-client mod: $output"
    echo "Independent NeoForge 1.21.1 build successfully."
}

main "$@" > >(tee "$LOG") 2>&1 || EXIT_CODE=$?
exit "$EXIT_CODE"
