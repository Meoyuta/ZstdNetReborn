# ZstdNet NeoForge 1.21.1

ZstdNet 为 Minecraft 1.21.1 的网络连接增加 Zstandard 压缩；客户端和服务端需要安装版本一致的 ZstdNet。本独立项目包含 NeoForge 双端模组及其共享模块，不包含较新 Minecraft 版本、Spigot 或 Fabric 工程。

## 环境要求

- Minecraft 1.21.1
- NeoForge 21.1.223
- Java 21

## 构建

在本目录运行 `bash ./build.sh`。脚本构建双端模组，将可分发 JAR 放在 `target/`，并将构建日志写入根目录 `build.log`。

构建脚本会将 1.21.1 产物复制为 `target/ZstdNet-1.21.1-neoforge-server-client-<version>.jar`。服务端及所有参与连接的客户端应安装相同构建版本。协议不兼容旧模组版本，请保持客户端与服务端版本同步。

新生成的客户端配置默认关闭（enabled=false）。启用后所有服务器都必须先通过 ZstdNet 能力探测，已取消服务器白名单和跳过探测的入口。普通服务器探测失败时会继续使用明文协议；当前不支持 LAN/集成服务器。

## 不兼容模组

以下模组与 ZstdNet **不能同时安装**。两者功能互斥，必须二选一：

| 模组 | Mod ID | 互斥原因 |
|---|---|---|
| Krypton 及其各分支 | `krypton` 等 | 两者都会占用 Minecraft 的 Netty 压缩槽位 |

同时安装时，ZstdNet 可能在接管管线时替换另一个压缩模组的 handler。这不是受支持的组合，也不是安全的运行时协商机制。启动游戏前请移除其中一个压缩模组。

## 功能

- 同端口 Zstandard 协议检测与 Netty 压缩。
- 运行时压缩等级调整和自动 benchmark。
- 上下行独立字典训练、导入、选择、导出及客户端同步。
- 通过 F8 选择 benchmark、管理状态和字典信息 overlay。
- 直接 RTT 探测和诊断报告生成。

See [README.md](README.md) for English usage notes, and [TECHNICAL.md](TECHNICAL.md) for implementation details.
