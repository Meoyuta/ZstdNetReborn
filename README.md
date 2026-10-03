# ZstdNet NeoForge 1.21.1

ZstdNet is an optimization mod that adds Zstandard (Zstd) compression to Minecraft 1.21.1 network connections. It compresses Minecraft network data with Zstd before transmission, substantially reducing server bandwidth consumption. The client and server must install the same ZstdNet version.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.223
- Java 21

## Build

Run `bash ./build.sh` from this directory. The script builds the client and server mod, places the completed JAR file in `target/`, and writes the build log to `build.log` in the same directory.

The build script copies the built mod artifact to `target/ZstdNet-1.21.1-neoforge-server-client-<version>.jar`. Install the same build on the server and every client that connects to it. This mod is still under development, and the updated protocol is incompatible with older versions, so keep the client and server versions synchronized.

After ZstdNet is installed on the client, every server must first pass the ZstdNet capability probe. Ordinary servers continue to use the plain protocol when probing fails; **the current version does not support integrated servers (this mod's features cannot be used in multiplayer/LAN play)**

## Incompatible Mods

The following mods **cannot be installed together** with ZstdNet:

| Mod | Mod ID | Conflict |
|---|---|---|
| Krypton and its forks | `krypton` and similar IDs | Both occupy Minecraft's Netty compression slots |

When both are installed, ZstdNet replaces the other compression mod's handler while taking over the pipeline. **This may not crash the game, but it may cause unexpected problems**, so **keep only one of these mods installed** before starting the game.

## Features

- Same-port Zstandard protocol detection and network packet compression through Netty injection
- Runtime manual compression-level adjustment and automatic benchmark tuning
- Independent uplink/downlink dictionary training, importing, selection, exporting, and client synchronization
- F8 overlay selection for benchmark, ZstdNet status, and dictionary information
- Direct RTT latency probing (shown in the player list) and diagnostic report generation

## Commands

### Commands Without Permission

- `/zstdnet ping`: a standalone TCP RTT measurement that can only be run by a player; the command replies with the measured latency
- `/zstdnet debug`: generates a one-time diagnostic report and returns its file path

### Commands Requiring Permission

_(The following commands require permission level 2.)_

- `/zstdnet start`, `/zstdnet stop`, `/zstdnet reload`: start, stop, or reload the ZstdNet service
- `/zstdnet complevel set <serverLevel> <clientLevel>`: set the server outbound level and the client outbound level target for new connections (each level is 1-22); the client applies its own local setting
- `/zstdnet benchmark start`: immediately start a compression benchmark
- `/zstdnet benchmark interval <minutes>`: set the benchmark interval
- `/zstdnet dictionary train [seconds]`: collect network samples and train a dictionary for the specified duration (600 seconds by default)
- `/zstdnet dictionary stop`: immediately stop sample collection and submit the dictionary training task
- `/zstdnet dictionary cancel`: immediately cancel the current dictionary training task
- `/zstdnet dictionary import <path>`: import a dictionary file or dictionary bundle **(`<path>` must be a complete path including the file name)**
- `/zstdnet dictionary export`: export the current dictionary bundle
- `/zstdnet dictionary switch <dictionary-name>`: switch the currently selected dictionary
- `/zstdnet dictionary unload`: unload the currently loaded dictionary
- `/zstdnet dictionary name <file> <dictionary-name>`: assign a name to a pending dictionary

See [TECHNICAL.md](TECHNICAL.md) for detailed information.

See [README.zh-CN.md](README.zh-CN.md) for Chinese usage notes.