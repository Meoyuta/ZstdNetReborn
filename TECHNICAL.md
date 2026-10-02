# ZstdNet NeoForge 1.21.1 技术文档

## 工程范围

本仓库是 Minecraft 1.21.1 / NeoForge 21.1.223 的双端独立工程，使用 Java 21。项目采用 NeoForge MDK/ModDev Gradle 的单一根工程，源码只使用标准 `src/main` 与 `src/test` 源集。目标版本参数集中在根目录 `gradle.properties`。

`mys.zstdnet.reborn.core` 提供帧协议、Netty 编解码器、流量统计、压缩 benchmark、字典存储/训练/同步；`mys.zstdnet.reborn.client` 提供客户端配置、连接选择和字典缓存；`mys.zstdnet.reborn.neoforge` 按入口、`client`、`network`、`command`、`mixin` 分包，提供 NeoForge 双端入口、同端口注入、命令、payload、screen 与 overlay。所有代码属于同一根工程，不再通过 Gradle 子项目互相依赖。

## 连接与协议

客户端从 `config/zstdnet-client.properties` 读取全局连接配置，当前只有 `enabled` 和 `compression-level` 两项；新生成配置默认 `enabled=false`、客户端等级为 6。启用后每个服务器都必须先执行独立 TCP 能力探测，不再支持服务器白名单或跳过探测：客户端发送 4 字节 `ZNP` 能力探测头（版本字节 `0x01`），服务端返回 5 字节响应（`ZNP`、响应字节 `0x02`、协议版本 `2`）后关闭探测连接，客户端缓存结果后才为后续真正的 Minecraft TCP 连接安装压缩管线。探测在后台线程执行，成功结果缓存 5 分钟，失败结果缓存 30 秒；连接建立和读取响应的单次超时约 1 秒。探测失败、超时或服务端不支持时记录 debug/warning 并继续使用普通明文连接。LAN/集成服务器仍不支持。共享 Netty codec 安装在入站 AES 解密后、packet splitter 前，以及出站 AES 加密前、packet framing 后。服务端先识别能力探测魔数，再识别 Zstandard frame magic 与协议版本；匹配后安装 ZstdNet pipeline 并避免原版压缩协商。原版 status ping 透传；未安装/不匹配的连接按配置返回拒绝信息。

协议 v2 使用双向持久 zstd 流，每个数据帧仍显式保留原始长度和压缩块长度。`storedTag == 0` 的小帧按原始字节直通；压缩帧由持久流解压。压缩等级或字典变化时重置相应流上下文，连接关闭时释放上下文。协议版本不同步时不提供旧协议兼容；解码器遇到压缩流无进展会立即以连接错误结束，不能在 event loop 上无限等待。

能力探测只用于连接选择，不会把探测字节注入 Minecraft 登录流。服务端的同端口 handler 在未决状态下仅接受完整探测魔数、返回固定响应并关闭连接；收到 Zstandard magic 才进入限流、握手和压缩管线安装流程。这样未安装 ZstdNet 的普通服务器不会收到压缩握手，客户端也不会因误装管线而破坏明文协议。

编码器在 event loop 上维护每连接 FIFO 待发送队列，write 只入队，flush 在 64 KiB 上限或 2 ms 截止窗到达时合并压缩；控制帧和数据帧共用同一队列。批量 promise 由聚合 promise 收口，失败时逐个通知并关闭连接；通道不可写时立即刷出，绝不丢帧，也不使用全局 worker 队列。持久 zstd 流建立在连续 TCP 字节流之上，所有入队字节必须按序进入同一压缩流。Netty ByteBuf 直接作为输入，压缩输出使用引用计数 ByteBuf，样本采集最多复制 4 KiB。代价是压缩期间 event loop 会被占用，可能造成服务端 tick/网络处理卡顿，应通过实服加入突发验证其性能。

## Sable 独立 UDP 兼容

Sable (`modId = sable`) 在 NeoForge 元数据中声明为 optional 软依赖，版本范围从 2.0.5 起；未安装 Sable 不影响 ZstdNet 加载。ZstdNet 不静态链接 Sable API。服务端同端口注入器采用两层排除：已知规则跳过带有 Sable UDP decoder 的本地 UDP channel；通用规则只接受 Netty `ServerChannel`，并拒绝 `DatagramChannel`，因此独立 UDP 监听器不会进入 TCP 接受注入。Minecraft 的本地 TCP channel 仍保留原有处理。

Sable 的激活令牌通过 Minecraft TCP 自定义 payload 传送，UDP 认证、数据报和 keep-alive 使用 Sable 自己的 datagram pipeline。ZstdNet 只压缩 Minecraft TCP 流量，不压缩 Sable UDP 数据报；Sable 的 UDP 超时回退行为不由 ZstdNet 改写。源码核对基于 Sable 上游提交 `6f2b321`（版本 2.0.5，Minecraft 1.21.1，NeoForge 21.1.228）。本项目目标为 NeoForge 21.1.223，因此该源码核对不等于已验证完整运行组合。

### 其他独立 UDP 模组

兼容分为两套规则：已知模组可增加专用的 pipeline/handler 排除（当前已覆盖 Sable）；未知模组走通用 `ServerChannel`/`DatagramChannel` 类型过滤。Simple Voice Chat (`modId = voicechat`) 当前实现使用独立 Java `DatagramSocket`（默认 UDP 24454），不注册到 Minecraft `ServerConnectionListener.channels`，所以天然不进入 ZstdNet 注入范围，不需要增加专用依赖或 API 链接。该判断适用于独立 socket；如果其他模组把 UDP 伪装为本地 `ServerChannel`，仍需增加其 handler 特征到已知排除表。

已完成 IDEA MCP 项目读取/搜索、增量构建、`ZstdNettyPipelineTest`、根工程 `./gradlew test`、`bash ./build.sh` 和发行 JAR 生成；发行 JAR 中确认 Sable dependency type 为 `optional`。当前 `settings.gradle` 是单一根工程，没有 `main` Gradle 子项目，因此 `./gradlew :main:test` 不适用，正确测试任务为 `./gradlew test`。尚未完成同时安装 ZstdNet/Sable 的真实双端联机测试，特别是 UDP 激活、持续收发、keep-alive 超时回退及服务端关闭清理；该组合仍需在匹配的 NeoForge 环境实测。

## Benchmark 与等级

服务端默认压缩等级为 9，`/zstdnet complevel set <1-22>` 可更改新连接使用的服务端等级；客户端新生成配置默认等级为 6，双方等级分别作用于各自的出站流。已建立连接保持协商时等级。定时 benchmark 和自动应用等级默认关闭。管理员可用 `/zstdnet benchmark start` 手动评估；候选范围通过 `/zstdnet benchmark setbaseline <min> <max>` 配置，闭区间必须满足 1 <= min <= max <= 22，默认范围为 5-13。等级选择同时考虑压缩后大小与编解码耗时，避免只追求压缩率而选出过慢等级。范围保存到 `config/zstdnet/server.properties` 的 `benchmark-baseline-min/max`；首次生成服务端配置时默认写入等级 9。Benchmark 从真实网络包采集有界样本，在独立 executor 上使用独立 codec 和样本快照，不复用在线连接上下文。调度周期仍由 `/zstdnet benchmark interval <分钟>` 管理；定时执行可在服务器属性中显式启用。

## 字典

字典以单个 ZIP bundle 保存，包含 `uplink.zdict`（最多 64 KiB）和 `downlink.zdict`（最多 128 KiB）。训练分别收集两个方向的样本并独立训练。bundle、选择状态和待命名字典存放在 `config/zstdnet/dict/`；客户端校验服务端字典 ID 后缓存并确认，之后才启用对应方向的字典压缩。字典失败原因使用 `INBOUND_ID_MISMATCH`、`OFFER_REJECTED`、`INVALID`、`IO` 枚举传递，不依赖错误文案匹配。服务端发现客户端 uplink ID 不匹配或拒绝 offer 时，双方该方向继续使用无字典流，并分别累计记录降级次数和当前降级连接数，避免连接因字典差异断开；当前协议仍没有服务端主动下发新 uplink 的路径。旧的单字典格式不兼容。

## 状态与诊断

F8 打开 overlay 选择界面，可选 benchmark、管理状态和字典状态；只有通过 F8 菜单关闭 overlay，离开世界时会清除状态且不持久化。界面数据约每秒更新一次，不阻塞玩家移动和交互。管理状态包括压缩等级、当前 RTT、服务器视角的每秒上/下行（原始与线路字节）、运行时长、进程内 benchmark 次数，以及字典上行降级累计数和当前降级连接数；overlay 使用“累计 / 当前”格式展示，这些数值同时写入 `/zstdnet debug` 报告中的 `dictionary_uplink_fallbacks` 与 `dictionary_active_fallback_connections` 字段。

`/zstdnet ping` 使用 nonce 请求/响应及 `System.nanoTime()` 测量 RTT，不复用 Minecraft 延迟值；该命令需要玩家来源以便返回结果。`/zstdnet debug` 无额外权限要求，玩家、控制台、命令方块和其他 `CommandSourceStack` 均可执行，在 `config/debug/` 写入一次性 UTF-8 诊断报告，报告中的 requester 使用命令源文本名称，包含连接、流量/压缩、benchmark、字典、服务端 tick、JVM 与玩家 RTT 拆分信息，不进行逐包持续磁盘记录。

报告还包括 `compress_frames`、`compress_batches`、同步/异步微秒数、降级次数、丢弃次数、最大帧耗时和大小直方图；`compress_dropped` 非零即表示严重协议缺陷。日志诊断中若 `server_tick_max_ms` 达到数秒，应优先按服务端主线程停顿处理，而不是把 Tab 延迟直接归因于 ZstdNet 网络压缩；Netty 压缩线程栈与服务端 tick 栈需分别判断。

连接诊断还会在 ZstdNet TCP pipeline 中安装 `zstdnet-diagnostics`。该 handler 不修改消息，仅记录 channel short id、remote、active/open 状态、最后一个入站/出站消息类型、pipeline、`close-request`、`disconnect-request`、`deregister-request`、`close-complete`、`channelInactive` 和 `exceptionCaught`；异常会保留完整 cause 到 FINE 日志。独立 `DatagramChannel` 在安装前被排除，因此不会安装该 handler、ZstdNet 编解码器或控制 handler。

历史异步编码器曾在队列溢出时关闭连接，随后改成丢包成功虽避免 promise 异常，却会破坏连续 zstd 流并令客户端 packet decoder 失步。当前改回 event loop 同步压缩，彻底移除队列上限和丢包路径，以阻塞发送生产者来保持字节流完整；需要监测其对 event loop 延迟和服务端 tick 的影响。停服字典训练最多等待 30 秒，超时后丢弃未发布结果并记录 warning。

## 构建与验证

唯一项目构建入口为 `bash ./build.sh`。脚本清理 `target/` 中旧 JAR，运行根项目的 `clean build`，并将 `build/libs` 的运行时 JAR 复制到 `target/`。根目录 `build.log` 记录该脚本输出，构建状态以日志末尾和脚本退出码为准。`./gradlew test` 可运行全部回归测试。发行前应检查 JAR 包含 NeoForge 双端入口、`core`/`client`/`neoforge` 包和内嵌 zstd-jni JarJar 依赖；真实服务端/客户端连接、压缩表现和 screen 行为仍需游戏内验证。
