# ZstdNet Technical Documentation

This document describes the current project's operation and protocol principles.

## 1. Project Structure

ZstdNet can be divided into four layers:

- core: Persistent Zstandard streams, frame format, Netty codecs, dictionary protocol, traffic statistics, and benchmarks. This layer is kept as independent from Minecraft as possible.
- client: Client compression-level configuration, capability-probe cache, connection-preparation queue, and client dictionary cache.
- neoforge: Mod lifecycle, same-port server injection, client Mixins, commands, management payloads, and overlay.
- test: Regression tests for the protocol, dictionaries, Netty pipelines, connection selection, and benchmarks.

The intended runtime environment is Minecraft 1.21.1, NeoForge 21.1.223, and Java 25. The project is built with Java 21.

## 2. Overall Connection Flow

A connection is processed in the following order:

1. The client intercepts Minecraft's connection entry point and obtains the target host and port.
2. The client performs the ZstdNet capability probe on a background thread; the probe does not block the game thread.
3. After a successful probe, the client stores one pending-installation record for that host and port.
4. After Minecraft creates the actual TCP Connection, ZstdNet reads the pending-installation record when the pipeline is configured.
5. The client installs the ZstdNet codecs and repositions them after the encryption pipeline is established.
6. The server receives child connections on Minecraft's ServerChannel and first determines whether each connection is a capability probe, a ZstdNet handshake, or ordinary Minecraft traffic.
7. After both sides confirm the protocol version, data is transferred over a persistent Zstandard stream on the same TCP byte stream.
8. When the connection closes, the compression streams, decompression executor, dictionary session, and connection-level statistics are released.

The client configuration only stores `compression-level`. Whether the compression pipeline is installed is determined entirely by the capability probe. If probing fails, times out, or returns a mismatched protocol version, the current connection immediately falls back to the ordinary protocol without ZstdNet.

## 3. Client Configuration and Connection Preparation

### 3.1 Client Configuration

`ClientConfig` reads the `compression-level` field from `config/zstdnet-client.properties`.

The `compression-level` field controls the client's outbound compression level. Its default is `compression-level=6`.

The configuration directory and file are created automatically on first startup. The compression level is restricted to 1 through 22; a missing or invalid value falls back to 6.

### 3.2 Connection Entry Point

`ConnectScreenMixin` calls `ZstdNetConnectHooks` at the `ConnectScreen.startConnecting` entry point. The connection address itself is not replaced; ZstdNet only calls `ZstdNetConnectionHooks.prepare(host, port)` to record the connection-preparation state.

When there is no valid probe result in the cache, the following actions are performed:

- Remove any existing pending-installation record for the address.
- Start one background probe.
- Continue the current Minecraft connection using the ordinary protocol.

When the cached result indicates support, the connection-preparation queue stores the host, port, client compression level, and a 15-second expiration time. At most eight pending-installation records are retained for one address; expired records are cleaned up by a new preparation request.

### 3.3 Installation on the Actual TCP Pipeline

`ConnectionMixin` invokes the installation logic after Minecraft finishes `configurePacketHandler`. The installation logic accepts only TCP `InetSocketAddress` connections. ZstdNet is not installed on:

- Sable-specific UDP pipelines.
- Other `DatagramChannel` instances.
- Pipelines without an available remote TCP address.
- Pipelines without a corresponding pending-installation record.

After a successful installation, the client creates `ZstdNettyEncoder` and `ZstdNettyDecoder` instances with a dictionary session. The `setEncryptionKey` Mixin callback then calls `reposition` again to ensure that the codecs remain on the correct side of the encryption boundary.

The inbound order is:

    Minecraft decryption -> ZstdNet decoder -> packet splitter -> Minecraft packet decoder

The outbound order is:

    Minecraft packet encoder -> packet prepender -> ZstdNet encoder -> Minecraft encryption

The actual pipeline repositions handlers according to whether `decrypt`/`encrypt` and `splitter`/`prepender` are present. If an anchor is missing, the current position is retained and a warning is logged.

## 4. Protocol Probe

### 4.1 Probe Bytes

The client sends the following through a temporary TCP socket:

    Z N P 0x01

The server returns:

    Z N P 0x02 0x02

The final byte identifies protocol version 2. The client validates the complete response byte by byte; any mismatch is treated as unsupported. The probe socket is closed after the complete response is received, so the probe bytes are never injected into the Minecraft login stream.

### 4.2 Asynchronous Execution and Caching

`CapabilityProbe` performs probes using a daemon executor. The probe parameters are:

- TCP connection timeout: 1 second.
- Socket read timeout: 1 second.
- Additional future timeout: approximately 1.25 seconds.
- Successful-result cache: 5 minutes.
- Failed-result cache: 30 seconds.
- Concurrent probes for the same host and port are merged through `IN_FLIGHT`.

The cache key uses the lowercase host name and port. The cache only determines whether subsequent connections may install compression; it does not change an already established ordinary connection.

## 5. Server Startup and Same-Port Injection

### 5.1 ZstdNet Service Lifecycle

ZstdNet starts from `ServerStartedEvent`. Startup performs the following operations:

1. Create the server configuration and dictionary storage.
2. Load the currently selected dictionary.
3. Create the dictionary trainer and benchmark.
4. Create `SamePortZstdInjector`.
5. Read Minecraft's `ServerConnectionListener.channels` through a Mixin accessor.
6. Iterate over the channels, exclude `DatagramChannel` instances, and add accept handlers only to TCP `ServerChannel` instances.
7. Record the injection status and begin accepting connections.

If injection fails, no `ServerChannel` can be found, or the accessor cannot read the field, the server remains on the vanilla network.

### 5.2 Mixin Accessor

`ServerConnectionListenerAccessor` accesses the `channels` field with `@Accessor("channels")`. Field binding is handled by Mixin application and the refmap. The injector copies the channel-future list after reading it and then filters for usable TCP channels.

### 5.3 Child-Connection Handling

When an `AcceptInjector` on the server's `ServerChannel` sees a new child `Channel`, it inserts `SamePortZstdHandler` at the beginning of that child's pipeline. The handler processes the connection until protocol detection is complete, then removes itself and delegates to either the formal ZstdNet pipeline or the ordinary Minecraft pipeline.

ZstdNet connections from one IP have two limits:

- At most 3 active connections.
- At most 10 handshake attempts per minute.

The handshake window is cleaned up after every 1024 attempts. The capability-probe branch runs before admission and therefore does not consume handshake or active-connection slots.

## 6. Server Protocol Detection

`SamePortZstdHandler` has three internal modes:

- UNDECIDED: waiting for enough bytes to identify the protocol.
- RAW: pass through ordinary Minecraft traffic.
- ZSTD: install and use the ZstdNet pipeline.

Detection proceeds as follows:

1. If a protocol-probe magic is received, return the fixed response and close the connection. This path is not rate-limited.
2. If a Zstd frame magic is received, perform rate-limit admission, read the stream header, and install ZstdNet.
3. For ordinary traffic, check whether it is a vanilla login packet:
   - Login traffic receives the configured server rejection packet and the connection is closed.
   - Other ordinary traffic enters RAW pass-through mode.
4. A connection that has not completed detection is closed after 10 seconds to prevent half-open connections from permanently consuming resources.

After entering ZSTD mode, the server creates directional dictionary sessions, installs the codecs, removes the vanilla compression-negotiation handlers, and immediately sends any pending dictionary control frames.

## 7. Protocol Structure

### 7.1 Magic and Stream Header

A ZstdNet data stream begins with the 4-byte Zstandard magic:

    28 B5 2F FD

When a dictionary session is used, a one-byte stream header `0x02` follows. A protocol-version mismatch causes an immediate protocol error.

### 7.2 Data Frames

Each data frame contains two VarInts followed by the payload:

    rawLength
    storedTag
    payload

Their meanings are:

- `rawLength`: the number of bytes after decompression.
- `storedTag == 0`: the payload contains uncompressed raw bytes.
- `storedTag != 0`: `storedTag >>> 1` is the compressed payload length.
- `storedTag & 1 == 1`: the frame uses the currently active dictionary.
- `storedTag & 1 == 0`: the frame does not use a dictionary.

The maximum declared raw length accepted by the protocol is 2 MiB + 64 KiB. Lengths, payload lengths, and VarInts are validated before decoding; an invalid frame immediately terminates the connection.

### 7.3 Control Frames

A control frame uses `rawLength == 0`, with the payload encoded as a control record. Control records are used for:

- Dictionary offers.
- Dictionary acknowledgements.
- Dictionary rejections.
- Persistent-stream resets.

Control frames and data frames share the same outbound FIFO, ensuring that a control record arrives before the data it governs. When a control record requires a response, the decoder sends the acknowledgement from the encoder context instead of passing through Minecraft's packet encoder and length prepender.

## 8. Outbound Compression and Batching

### 8.1 Persistent Zstandard Streams

Each direction of each connection has its own `ZstdPersistentStreamCodec`. The encoder creates or replaces the persistent stream according to the current compression level and outbound dictionary ID:

- When the level changes, send a stream reset and create a new stream.
- When the dictionary ID changes, send a stream reset and create a new stream.
- When the stream closes, release the native codec resources.

Frames on one connection enter the same persistent stream in write order. Stream state is never shared between connections.

### 8.2 FIFO Batching Queue

After `ZstdNettyEncoder.write` receives a `ByteBuf`, it first places it in a connection-level FIFO. The batching rules are:

- Maximum raw data per batch: 64 KiB.
- Deadline window: 2 ms.
- Maximum pending packets: 4096.
- A single message that reaches 64 KiB is sent separately.
- Control frames and ordinary data share the queue.

When the size limit is reached, the channel becomes unwritable, or the deadline expires, the queue is merged into one contiguous raw `ByteBuf` and compressed through the persistent stream. Minecraft's upstream packet splitter/prepender continues to define packet boundaries; ZstdNet only merges contiguous bytes and does not redefine the Minecraft packet format.

Each batch uses an aggregate promise. On success, the original promises are completed one by one; on failure, each promise is failed and the connection is closed. When the connection closes, all unsent `ByteBuf` instances and promises are released.

## 9. Inbound Decompression

The decoder parses headers, control frames, and ordinary frames on the event loop. Compressed frames with a raw length below 64 KiB are decompressed synchronously.

When the raw length reaches 64 KiB:

1. The decoder retains the payload from the input `ByteBuf`.
2. It submits the decompression task to the connection's dedicated single-thread daemon executor.
3. The event loop pauses further parsing for that connection to preserve persistent-stream order.
4. The worker returns the result to the event loop when it completes.
5. A successful result is passed to the next handler; a failure is propagated and the connection is closed.
6. The event loop triggers the decoder again to process bytes accumulated while the worker was running.

Only one decompression task runs per connection. The single-thread executor protects the persistent Zstd stream context and preserves frame-delivery order. When the connection closes or fails, the executor is stopped and the persistent stream is closed.

## 10. Dictionary Synchronization

### 10.1 Direction and Size

The server and client maintain separate upload and download dictionaries:

- Maximum server-to-client dictionary size: 128 KiB.
- Maximum client-to-server dictionary size: 64 KiB.
- Dictionaries are validated by ID and byte content.
- If a dictionary does not match, that direction falls back to a stream without a dictionary.

### 10.2 Control Flow

A typical flow is:

1. Send the stream header after establishing the ZstdNet stream.
2. The offer contains the direction, dictionary ID, dictionary size, and dictionary bytes.
3. The receiver validates the direction, length, and ID.
4. Return an acknowledgement after successful validation.
5. Activate the dictionary for that direction after the sender receives the acknowledgement.
6. On validation failure or peer rejection, send a rejection and continue with a stream without a dictionary.

Dictionary acknowledgements, rejections, and errors are recorded with the `DictionaryFailure` enum. An incomplete client dictionary download during disconnect is logged as an I/O failure.

### 10.3 Suppressing Duplicate Offers

The server records download-dictionary delivery state by remote host name and dictionary ID. If the same host has already received the same ID, later short connections skip the duplicate offer. When the dictionary ID changes, the record is updated and the offer is sent again. The host key does not include the temporary port that changes between connections.

## 11. Vanilla Compression Negotiation and Third-Party Mods

After ZstdNet is installed, `MinecraftCompressionDisabler` handles the vanilla login compression packet and removes the `compress` and `decompress` handlers from the pipeline. It performs this operation at several points, including inbound processing, outbound processing, an immediate task, and a 50 ms delayed task, to cover cases where vanilla handlers are added late.

If a handler being removed does not belong to `net.minecraft.*`, a warning is logged to leave a trace of the installation conflict, because directly removing another mod's handler may cause unintended behavior.

## 12. Compression Levels and Benchmarking

The server's default outbound level is 9 and the client's default outbound level is 6. Each level applies independently to the persistent stream in that direction.

Server commands can change the level used by new connections. The benchmark uses an independent codec and sampled snapshots to test candidate levels, recording compressed size and encode/decode time before selecting a level within the configured range. Online persistent streams are never reused by the benchmark; a new level applies to new connections or the next stream reset.

## 13. Statistics, Status, and Diagnostics

### 13.1 Traffic Statistics

From the server's perspective, the following are recorded:

- `rawUp / wireUp`: raw and on-the-wire bytes sent to the client.
- `rawDown / wireDown`: raw and on-the-wire bytes received from the client.
- Active and cumulative connection counts.
- Upstream and downstream rates sampled every 500 ms.
- The ratio of on-the-wire bytes to raw bytes.

### 13.2 Compression Metrics

`CompressionMetrics` uses concurrent counters to record:

- Frame count.
- Number of batches that were merged.
- Synchronous/asynchronous duration in microseconds.
- Fallback count.
- Drop count.
- Maximum frame duration.
- Raw-data-size histogram.

A non-zero `compress_dropped` should be treated as a protocol-defect signal indicating that frames may have been dropped.

### 13.3 Status and Debugging

Connection and service statuses include:

- `active`
- `not_installed`
- `inject_failed`
- `probe_failed`
- `server_disabled`
- `peer_unsupported`

The management payload and overlay display connection status, upstream/downstream levels, traffic, compression ratio, dictionary connection count, dictionary fallback count, and RTT.

`/zstdnet debug` generates a one-time UTF-8 report under `config/debug/`. The report includes compression metrics, traffic, benchmarks, dictionary information, server ticks, JVM and GC data, thread dumps, and player RTT information.

## 14. Commands and Permissions

The command root is `/zstdnet`.

No permission is required for:

- `ping`: a direct TCP RTT measurement available only to players.
- `debug`: generate a diagnostic report and return its file path.

Permission level 2 is required for:

- `start`, `stop`, `reload`: manage the server-side Zstd service.
- `complevel set <1-22>`: set the server compression level.
- `benchmark start`, `benchmark interval <minutes>`: control benchmarking.
- `dictionary train <seconds>`, `stop`, `cancel`: control dictionary training.
- `dictionary import <path>`, `export`, `switch <dictionary-name>`, `unload`, `name <file> <dictionary-name>`: manage dictionaries.

Management commands are valid only on dedicated servers. Dictionary training, switching, and unloading affect subsequent connections. When `dictionary switch` changes the dictionary, the server disconnects existing players to prevent persistent-stream contexts and dictionary state from being mixed.

## 15. Connection Shutdown and Error Handling

When a connection closes:

- The encoder cancels its scheduled flush and releases pending `ByteBuf` instances.
- The encoder and decoder close their persistent Zstandard streams.
- The decoder stops the asynchronous decompression executor.
- The dictionary session clears download and activation state.
- The server removes the connection from active-IP counts and statistics.
- An incomplete dictionary download records the failure reason.

The current connection is closed, or vanilla networking is retained, in the following cases:

- Invalid frame length, VarInt, control record, or protocol version.
- Decompression failure or persistent-stream failure.
- A dictionary-compressed frame arrives before the dictionary is activated.
- Accessor or ServerChannel injection failure.
- A half-open connection does not complete protocol detection before the handshake timeout.

A failed probe does not close the real Minecraft connection; it makes that connection continue with the ordinary protocol.

## 16. UDP and Third-Party Network Pipelines

ZstdNet handles Minecraft TCP only. ZstdNet codecs are not installed on Sable's UDP pipeline, independent `DatagramChannel` instances, or non-Minecraft UDP sockets. Sable remains optional through an optional dependency and runtime pipeline recognition; ZstdNet does not statically link the Sable API.

The server injector uses the Mixin accessor, `ServerChannel` type filtering, and `DatagramChannel` exclusion together to avoid treating an independent UDP listener as a Minecraft TCP acceptor.

## 17. Build

The project build entry point is:

    bash ./build.sh

The regular Gradle regression tests are run with:

    ./gradlew test
