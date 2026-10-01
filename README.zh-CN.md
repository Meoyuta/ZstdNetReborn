# ZstdNet NeoForge 1.21.1

ZstdNet 为 Minecraft 1.21.1 的网络连接增加 Zstandard 压缩；客户端和服务端需要安装版本一致的 ZstdNet。本独立项目包含 NeoForge 双端模组及其共享模块，不包含较新 Minecraft 版本、Spigot 或 Fabric 工程。

## 环境要求

- Minecraft 1.21.1
- NeoForge 21.1.223（FML loader 4.0.42）
- Java 21

## 构建

在本目录运行 `bash ./build.sh`。脚本构建双端模组，将可分发 JAR 放在 `target/`，并将构建日志写入根目录 `build.log`。

产物名称为 `ZstdNet-1.21.1-neoforge-server-client-1.1.7.2-beta.jar`。服务端及所有参与连接的客户端应安装相同构建版本。协议不兼容旧模组版本，请保持客户端与服务端版本同步。

## 功能

- 同端口 Zstandard 协议检测与 Netty 压缩。
- 运行时压缩等级调整和自动 benchmark。
- 上下行独立字典训练、导入、选择、导出及客户端同步。
- 通过 F8 选择 benchmark、管理状态和字典信息 overlay。
- 直接 RTT 探测和诊断报告生成。

详细用法见本文件；实现细节见 [TECHNICAL.md](TECHNICAL.md)。
