# ZstdNet NeoForge 1.21.1

ZstdNet adds Zstandard compression to Minecraft 1.21.1 connections when the client and server use matching ZstdNet builds. This standalone project contains the NeoForge server-client mod and the shared modules it needs; it does not include the newer Minecraft variants or the Spigot/Fabric projects.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.223
- Java 21

## Build

Run `bash ./build.sh` from this directory. The script builds the server-client mod, stores the distributable JAR under `target/`, and writes the build log to `build.log`.

The artifact is copied to `target/ZstdNet-1.21.1-neoforge-server-client-<version>.jar` by the build script. Install the same mod build on the server and every participating client. Keep the mod versions synchronized; older wire-protocol versions are intentionally unsupported.

## Features

- Same-port Zstandard protocol detection and Netty compression.
- Runtime compression-level control and automatic benchmarking.
- Directional dictionary training, import, selection, export, and client synchronization.
- F8 overlay selector for benchmark, management, and dictionary information.
- Direct RTT probing and diagnostic report generation.

See [README.zh-CN.md](README.zh-CN.md) for Chinese usage notes and [TECHNICAL.md](TECHNICAL.md) for implementation details.
