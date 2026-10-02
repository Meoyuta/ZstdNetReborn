# ZstdNet NeoForge 1.21.1

ZstdNet 是一个为 Minecraft 1.21.1 的网络连接增加 Zstandard(Zstd) 压缩的优化模组，它使得Minecraft将网络数据进行zstd压缩之后再进行传输，由此大大减少服务器的带宽消耗

## 环境要求

- Minecraft 1.21.1
- NeoForge 21.1.223
- Java 21

## 构建

在本目录运行 `bash ./build.sh`。脚本构建双端模组，此脚本将构建完成的 jar 文件放在 `target/`，并将构建日志写入同目录的 `build.log`。

构建脚本会将构建好的模组产物复制到 `target/ZstdNet-1.21.1-neoforge-server-client-<version>.jar`。服务端及所有参与连接的客户端应安装相同构建版本。因为模组尚处于开发阶段，更新后的协议不兼容旧版本，请保持客户端与服务端版本同步

客户端安装 ZstdNet 后，所有服务器都必须先通过 ZstdNet 探测。普通服务器探测失败时会继续使用明文协议；**当前版本不支持集成服务器（无法在联机中使用此模组的功能）**

## 不兼容模组

以下模组与 ZstdNet **不能同时安装**

| 模组 | Mod ID | 冲突原因 |
|---|---|---|
| Krypton 及其各分支 | `krypton` 等 | 两者都会占用 Minecraft 的 Netty 压缩槽位 |

同时安装时，ZstdNet 在接管管线时会替换另一个压缩模组的 handler。**这可能不会导致崩溃，但可能会导致一些非预期的问题**，因此启动游戏前请**只保留其中一个模组**

## 功能

- 基于 Netty注入的同端口 Zstandard 协议检测与网络包压缩
- 运行时压缩等级的手动调整和自动 benchmark 调整
- 上下行独立字典训练、导入、选择、导出及客户端同步
- 按 F8 选择 benchmark/ZstdNet状态/字典信息的叠加层信息展示
- 直接 RTT 探测的延迟（在玩家列表显示）和诊断报告生成

## 指令

### 无权限指令

- `/zstdnet ping`：只能由玩家执行的一次独立的 TCP RTT 测量，命令回显为测得的延迟结果
- `/zstdnet debug`：生成一次性诊断报告，并返回报告文件路径

### 需权限指令

_（以下指令需要2级权限）_

- `/zstdnet start`、`/zstdnet stop`、`/zstdnet reload`：启动、停止或重新加载 ZstdNet 服务
- `/zstdnet complevel set <1-22>`：设置新连接使用的服务端压缩等级
- `/zstdnet benchmark start`：立即启动一次压缩 benchmark
- `/zstdnet benchmark interval <分钟>`：设置 benchmark 的执行间隔
- `/zstdnet dictionary train [秒]`：采集指定时长的网络样本并训练字典（默认 600 秒）
- `/zstdnet dictionary stop`：立即停止采集样本并提交字典训练任务
- `/zstdnet dictionary cancel`：立即取消当前字典训练
- `/zstdnet dictionary import <路径>`：导入字典文件或字典 bundle **（`<路径>`需为包含文件名的完整路径）**
- `/zstdnet dictionary export`：导出当前字典 bundle
- `/zstdnet dictionary switch <字典名>`：切换当前选用的字典
- `/zstdnet dictionary unload`：卸载当前已加载的字典
- `/zstdnet dictionary name <文件> <字典名>`：为待命名字典设置字典名

阅读[TECHNICAL.zh-CN.md](TECHNICAL.zh-CN.md) 获取详细信息。

See [README.md](README.md) for English usage notes.
