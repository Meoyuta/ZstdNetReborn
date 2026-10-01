# ZstdNet NeoForge 1.21.1 技术文档

## 工程范围

本仓库是 Minecraft 1.21.1 / NeoForge 21.1.223 的双端独立工程，使用 Java 21。根 Gradle 工程仅包含 `core`、`mod-common` 和 `neoforge` 三个子工程；NeoForge 源集使用 `neoforge/src/client` 与 `neoforge/src/1_21_1`。目标版本参数集中在根目录 `gradle.properties`。

`core` 提供帧协议、Netty 编解码器、流量统计、压缩 benchmark、字典存储/训练/同步；`mod-common` 提供共享客户端配置、连接选择和字典缓存；`neoforge` 提供 1.21.1 双端入口、同端口注入、命令、payload、screen 与 overlay。

## 连接与协议

客户端从 `config/zstdnet-client.properties` 读取连接选择配置。共享 Netty codec 安装在入站 AES 解密后、packet splitter 前，以及出站 AES 加密前、packet framing 后。服务端通过 Zstandard frame magic 与协议版本检测连接，匹配后安装 ZstdNet pipeline 并避免原版压缩协商。原版 status ping 透传；未安装/不匹配的连接按配置返回拒绝信息。

协议 v2 使用双向持久 zstd 流，每个数据帧仍显式保留原始长度和压缩块长度。压缩等级或字典变化时重置相应流上下文，连接关闭时释放上下文。协议版本不同步时不提供旧协议兼容。

## Benchmark 与等级

手动 `/zstdnet complevel set <1-22>` 可设置完整等级范围；自动 benchmark 只比较 5 到 13 级，完成后可能覆盖临时手动等级。Benchmark 从真实网络包采集有界样本，并使用有界代表数据集测量 codec 时间和压缩结果；样本不足时等待后重试。调度器独立于服务端 tick 定时检查，只在周期到期且存在有效 ZstdNet 连接时触发。周期存储于 `config/zstdnet/server.properties`，可通过 `/zstdnet benchmark interval <分钟>` 动态修改。

## 字典

字典以单个 ZIP bundle 保存，包含 `uplink.zdict`（最多 64 KiB）和 `downlink.zdict`（最多 128 KiB）。训练分别收集两个方向的样本并独立训练。bundle、选择状态和待命名字典存放在 `config/zstdnet/dict/`；客户端校验服务端字典 ID 后缓存并确认，之后才启用对应方向的字典压缩。旧的单字典格式不兼容。

## 状态与诊断

F8 打开 overlay 选择界面，可选 benchmark、管理状态和字典状态；只有通过 F8 菜单关闭 overlay，离开世界时会清除状态且不持久化。界面数据约每秒更新一次，不阻塞玩家移动和交互。管理状态包括压缩等级、当前 RTT、服务器视角的每秒上/下行（原始与线路字节）、运行时长和进程内 benchmark 次数。

`/zstdnet ping` 使用 nonce 请求/响应及 `System.nanoTime()` 测量 RTT，不复用 Minecraft 延迟值。`/zstdnet debug` 无额外权限要求，在 `config/debug/` 写入一次性 UTF-8 诊断报告，包含连接、流量/压缩、benchmark、字典、服务端 tick、JVM 与玩家 RTT 拆分信息，不进行逐包持续磁盘记录。

## 构建与验证

唯一项目构建入口为 `bash ./build.sh`。脚本清理 `target/` 中旧 JAR，运行 NeoForge 1.21.1 Gradle 构建，并将产物复制到 `target/`。根目录 `build.log` 记录该脚本输出，构建状态以日志末尾和脚本退出码为准。`./gradlew :core:test` 可运行共享核心回归测试。发行前应检查 JAR 包含 NeoForge 双端入口、共享模块与 zstd-jni；真实服务端/客户端连接、压缩表现和 screen 行为仍需游戏内验证。
