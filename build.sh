#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$ROOT/target"
LOG="$ROOT/build.log"
EXIT_CODE=0

main() {
    mkdir -p "$TARGET" || return 1
    find "$TARGET" -type f -name '*.jar' -delete || return 1

    "$ROOT/gradlew" --no-daemon clean build \
        -Pminecraft_version=1.21.1 \
        -Pjava_version=21 \
        -Pneoforge_version=21.1.223 || return 1

    local jar
    jar="$(find "$ROOT/build/libs" -maxdepth 1 -type f -name 'zstdnet-*.jar' ! -name '*-sources.jar' -print -quit)"
    if [[ -z "$jar" || ! -f "$jar" ]]; then
        echo "Expected NeoForge runtime jar was not found under $ROOT/build/libs."
        return 1
    fi

    local jar_name version output
    jar_name="${jar##*/}"
    version="${jar_name#zstdnet-}"
    version="${version%.jar}"
    output="$TARGET/ZstdNet-1.21.1-neoforge-server-client-$version.jar"
    cp -f "$jar" "$output" || return 1
    echo "Built NeoForge 1.21.1 server-client mod: $output"
    echo "Independent NeoForge 1.21.1 build successfully."
}

main "$@" > >(tee "$LOG") 2>&1 || EXIT_CODE=$?
exit "$EXIT_CODE"
