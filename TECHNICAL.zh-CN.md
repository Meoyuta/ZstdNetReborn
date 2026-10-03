# ZstdNet 技术文档

本文档描述当前项目的运行方式和协议原理

## 1. 工程组成

ZstdNet 可分为四层：

- core：Zstandard 持久流、帧格式、Netty 编解码器、字典协议、流量统计和 benchmark,这一部分尽量与Minecraft独立
- client：协议探测缓存、连接准备队列和客户端字典缓存
- neoforge：模组生命周期、服务端同端口注入、客户端 Mixin、命令、管理 payload 和 overlay
- test：协议、字典、Netty 管线、连接选择和 benchmark 的回归测试

理想的本模组运行环境为 Minecraft 1.21.1、NeoForge 21.1.223 和 Java 25，构建使用 Java 21

## 2. 总体连接流程

一次连接按以下顺序处理：

1. 客户端拦截 Minecraft 的连接入口,取得目标主机和端口
2. 客户端在后台线程执行 ZstdNet 协议探测；_探测不会阻塞游戏线程_
3. 探测成功后,客户端为同一 host:port 保存一次待安装连接记录
4. Minecraft 创建真正的 TCP Connection 后,ZstdNet 在管线配置完成时读取待安装记录
5. 客户端安装 ZstdNet 编解码器,并在加密管线建立后重新定位编解码器
6. 服务端在 Minecraft 的 ServerChannel 上接收子连接,先识别探测当前连接是 ZstdNet 握手还是普通 Minecraft 流量
7. 双方确认协议版本后,使用同一条 TCP 字节流上的持久 Zstandard 流传输数据
8. 连接关闭时释放压缩流,解压 executor、字典会话和连接级统计状态

是否安装压缩管线完全由探测结果决定。探测失败、超时或响应版本不匹配时,当前连接直接使用普通协议。客户端和服务端必须使用当前相同构建版本，不提供本地客户端等级覆盖

## 3. 客户端配置与连接准备

### 3.1 客户端配置

客户端不再提供手动选择压缩等级的配置。客户端出站等级由服务端在协议探测响应中下发，因此每条 ZstdNet 连接都使用服务端当前同步的等级

### 3.2 连接入口

ConnectScreenMixin 在 ConnectScreen.startConnecting 入口调用 ZstdNetConnectHooks。连接地址本身不会被替换,ZstdNet 只调用 ZstdNetConnectionHooks.prepare(host, port) 记录连接准备状态

若当缓存中没有有效的探测结果时,则会做以下操作：

- 删除该地址已有的待安装记录
- 最多等待探测 300 ms；若未成功完成则启动或保留后台探测
- 探测失败或超时时，当前 Minecraft 连接按普通协议继续

当缓存结果为支持时,连接准备队列写入主机、端口、客户端压缩等级和 15 秒过期时间；同一地址最多保留 8 条待安装记录,过期记录会在新的准备请求中清理

### 3.3 真正的 TCP 管线的安装

ConnectionMixin 在 Minecraft configurePacketHandler 完成后调用安装逻辑。安装逻辑只接受 TCP InetSocketAddress,以下管线不会安装 ZstdNet：

- Sable 专用 UDP 管线
- 其他 DatagramChannel
- 没有可用远端 TCP 地址的管线
- 没有对应待安装连接记录的管线

安装成功后,客户端创建带字典会话的 ZstdNettyEncoder 和 ZstdNettyDecoder。随后 setEncryptionKey 的 Mixin 回调再次调用 reposition,确保编解码器位于正确的加密边界

入站顺序为：

    Minecraft 解密 -> ZstdNet decoder -> packet splitter -> Minecraft packet decoder

出站顺序为：

    Minecraft packet encoder -> packet prepender -> ZstdNet encoder -> Minecraft 加密

实际管线会根据 decrypt/encrypt、splitter/prepender 是否存在来移动 handler。锚点缺失时,将保留当前位置并记录 warning 日志

## 4. 协议探测

### 4.1 探测字节

客户端通过临时 TCP socket 发送：

    Z N P 0x01

服务端返回：

    Z N P 0x02 protocolVersion clientLevel

响应固定为 6 字节：固定前缀加上服务端选择的客户端压缩等级（1-22）。客户端逐字节校验并拒绝非法格式响应,因此客户端和服务端必须是相同版本。探测 socket 在收到完整响应后关闭,不会把探测字节注入 Minecraft 登录流

### 4.2 异步执行和缓存

ProtocolProbe 使用 daemon executor 执行探测,下列是探测相关的参数：

- TCP 连接超时：1 秒
- socket 读取超时：1 秒
- future 额外超时：约 1.25 秒
- 成功结果缓存：5 分钟
- 失败结果缓存：30 秒
- 同一 host:port 的并发探测通过 IN_FLIGHT 合并

缓存键使用小写主机名和端口；缓存只决定后续连接是否可以安装压缩,不会改变当前已经建立的普通连接

## 5. 服务端启动和同端口注入

### 5.1 ZstdNet服务的生命周期

服务端在 ServerStartedEvent 中启动 ZstdNet,启动时将做以下操作：

1. 创建服务端配置和字典存储
2. 加载当前选择的字典
3. 创建字典训练器和 benchmark
4. 创建 SamePortZstdInjector
5. 通过 Mixin Accessor 读取 Minecraft ServerConnectionListener.channels
6. 遍历channels,排除 DatagramChannel,只向 TCP ServerChannel 添加接受器
7. 记录注入状态并开始接受连接

注入失败、找不到 ServerChannel 或 Accessor 读取失败时,服务端将保持 vanilla 网络.

### 5.2 Mixin Accessor

ServerConnectionListenerAccessor 使用 @Accessor("channels") 访问 channels 字段；字段绑定由 Mixin 应用和 refmap 处理。注入器读取后复制 ChannelFuture 列表,再筛选可用 TCP channel

### 5.3 子连接处理

服务端 ServerChannel 上的 AcceptInjector 看到新的子 Channel 后,在其管线最前面加入 SamePortZstdHandler。该 handler 负责在协议判定完成之前处理连接,判定完成后移除自身并交给正式的 ZstdNet 管线或普通 Minecraft 管线

同一 IP 的 ZstdNet 连接有两类限制：

- 活跃连接最多 3 个
- 每分钟握手尝试最多 10 次

握手窗口每 1024 次尝试清理一次过期记录；协议探测分支发生在 admit 之前,将不会占用握手和活跃连接名额

## 6. 服务端协议判定

SamePortZstdHandler 有三个内部模式：

- UNDECIDED：等待足够字节以判断协议
- RAW：普通 Minecraft 流量透传
- ZSTD：安装并使用 ZstdNet 管线

判定顺序如下：

1. 如果收到协议探测魔数,返回固定响应并关闭连接；该路径不进入限流
2. 如果收到 Zstd frame magic,执行限流准入,读取流头并安装 ZstdNet
3. 如果是普通流量,检查是否为原版 login 包：
   - login 流量按服务端配置返回拒绝包并关闭
   - 其他普通流量进入 RAW 透传
4. 未完成判定的连接在 10 秒后关闭,避免半连接永久占用资源

进入 ZSTD 模式后,服务端创建方向性的字典会话、安装编解码器、移除原版压缩协商 handler,并立即发送待处理的字典控制帧（若有字典）

## 7. 协议结构

### 7.1 Magic 和 Stream Header

ZstdNet 数据流首先发送 4 字节 Zstandard magic：

    28 B5 2F FD

使用字典会话时,随后将发送 1 字节的 Stream Header：`0x02`。协议版本不匹配会导致立即抛出协议错误

### 7.2 数据帧

每个数据帧包含两个 VarInt：

    rawLength
    storedTag
    payload

含义如下：

- rawLength：解压后原始字节数
- storedTag == 0：payload 是未压缩原始字节
- storedTag != 0：storedTag >>> 1 是压缩 payload 长度
- storedTag & 1 == 1：该帧使用当前已激活的字典
- storedTag & 1 == 0：该帧不使用字典

协议接受的声明原始长度上限为 2 MiB + 64 KiB。长度、payload 长度和 VarInt 都在解码前校验,异常帧会直接结束连接

### 7.3 控制帧

控制帧使用 rawLength == 0,payload 采用控制记录格式。控制记录用于：

- 字典 offer
- 字典 acknowledge
- 字典 reject
- 持久流 reset

控制帧和数据帧共享同一出站 FIFO,保证控制记录在对应数据之前到达。收到需要回复的控制记录时,decoder 从 encoder context 发送确认,不会经过 Minecraft 的 packet encoder 和 length prepender

## 8. 出站压缩和批量

### 8.1 持久 Zstandard 流

每个连接的每个方向拥有自己的 ZstdPersistentStreamCodec。encoder 根据当前压缩等级和出站字典 ID 创建或替换持久流：

- 等级改变时发送 stream reset 并创建新流
- 字典 ID 改变时发送 stream reset 并创建新流
- 流关闭时释放 native codec 资源

同一连接中的帧按写入顺序进入同一持久流,不能跨连接共享流状态

### 8.2 FIFO 批量队列

ZstdNettyEncoder.write 收到 ByteBuf 后先进入连接级 FIFO。批量规则为：

- 批量原始数据上限：64 KiB
- 延迟截止窗：2 ms
- 待处理包上限：4096
- 单个达到 64 KiB 的消息单独发送
- 控制帧和普通数据共用队列

当到达上限、通道不可写或截止时间到达时,队列被合并为一个连续 raw ByteBuf,再通过持久流压缩。上游 Minecraft 的包边界仍由原有 packet splitter/prepender 负责；ZstdNet 只合并连续字节,不会重新定义 Minecraft 包的格式

注意：每个批次使用聚合 promise。写入成功时逐个完成原始 promise；写入失败时逐个报告失败并关闭连接。关闭连接时将释放所有尚未发送的 ByteBuf 和 promise

## 9. 入站解压

decoder 在 event loop 中解析 header、控制帧和普通帧；小于 64 KiB 的压缩帧将直接同步解压

原始长度达到 64 KiB 时：

1. decoder 从输入 ByteBuf 保留 payload
2. 将解压任务提交到共享有界压缩 worker 池；每个连接同时仍只允许一个任务在运行
3. event loop 暂停继续解析该连接,保持持久流顺序
4. worker 完成后回到 event loop
5. 成功结果交给后续 handler,失败则传播异常并关闭连接
6. event loop 重新触发 decoder,处理 worker 期间累积的字节

每个连接只有一个解压任务在运行；单飞规则保护 Zstd 持久流上下文并保证帧交付顺序，同时让多个连接共享 worker 线程。worker 队列满时回退同步解压；异步任务运行期间，入站 cumulation 上限为 8 MiB，达到上限后暂停 Netty auto-read。

## 10. 字典同步

### 10.1 方向和大小

服务端和客户端分别维护上行、下行字典：

- 服务端下发字典最大 128 KiB
- 客户端上行字典最大 64 KiB
- 字典通过 ID 和字节内容校验
- 字典不匹配时对应方向降级为无字典流

### 10.2 控制流程

典型流程为：

1. 建立 ZstdNet 流后发送 stream header
2. offer 包含方向、字典 ID、字典大小和字典字节
3. 接收方校验方向、长度和 ID
4. 校验通过后返回 acknowledge
5. 发送方收到 acknowledge 后激活对应方向字典
6. 校验失败或对方拒绝时发送 reject,并继续使用无字典流

字典确认、拒绝和异常通过 DictionaryFailure 枚举记录。断开期间未完成的客户端字典下载会记录 IO 失败的日志

### 10.3 重复下发抑制

服务端使用远端主机名和字典 ID 记录下行字典发送状态。同一主机已收到相同 ID 时,后续短连接跳过重复 offer，改为发送轻量方向确认；客户端只有在本地字典 ID 相同时才激活确认。字典 ID 变化时更新记录并重新发送完整 offer。主机键不会包含每次连接变化的临时端口

## 11. 原版压缩协商与第三方模组

ZstdNet 安装后,MinecraftCompressionDisabler 处理原版 login compression packet,并移除管线中的 compress 和 decompress handler。该操作会在入站、出站、立即任务和 50 ms 延迟任务多个时点执行,以覆盖原版 handler 延迟加入的情况

注意：若被移除的 handler 类名不属于 net.minecraft.*,会记录 warning 以留下安装产生的冲突痕迹，因为直接移除其他模组的 handler 可能会发生非预期的问题

## 12. 压缩等级和 benchmark

服务端默认出站等级为 9,客户端默认出站等级为 6。双方等级分别作用于各自方向的持久流。服务端在每次成功协议探测响应中下发客户端等级

/zstdnet complevel set <serverLevel> <clientLevel> 会修改两个方向的出站等级。新连接通过协议探测接收客户端等级；成功探测缓存有效期为 5 分钟，因此等级变化传递到新连接最长可能延迟 5 分钟。benchmark 使用独立 codec 和采样快照测试候选等级,同时记录压缩后大小和编解码耗时,再根据配置范围选择等级。在线连接的持久流不会被 benchmark 复用；等级应用到新连接或下一次流重置

## 13. 统计、状态和诊断

### 13.1 流量统计

服务端从自身视角记录：

- rawUp / wireUp：发送给客户端的原始和线路字节
- rawDown / wireDown：从客户端收到的原始和线路字节
- 活跃连接数和累计连接数
- 每 500 ms 采样一次的上下行速率
- 线路字节占原始字节的比例

### 13.2 压缩 metrics

CompressionMetrics 使用并发计数器记录：

- frame 数量
- 发生批量合并的批次数
- 同步/异步耗时微秒
- 降级次数
- `compress_queued_dropped`：8 MiB 出站队列超限时、在进入持久流前安全丢弃的排队包数量；不会造成持久流失步，是安全行为
- `compress_frames_lost`：进入持久流后丢失的帧数量；这是严重的持久流一致性错误，通常会导致持久流失步，应立即排查
- 最大帧耗时
- 原始数据大小直方图

`compress_sync_us` 和 `compress_async_us` 只统计 codec 压缩阶段；不包含 Netty `ctx.write` 完成和网络 flush 的时间。

`compress_queued_dropped` 表示安全背压丢包；`compress_frames_lost` 非零表示持久流缺陷

### 13.3 状态和 debug

连接/服务状态包括：

- active
- inject_failed
- probe_failed
- server_disabled

管理 payload 和 overlay 显示连接状态、上下行等级、流量、压缩比例、字典连接数、字典降级数和 RTT

/zstdnet debug 会在 config/debug/ 生成一次性 UTF-8 报告,包含压缩 metrics、流量、benchmark、字典、服务端 tick、JVM、GC、线程栈和玩家 RTT 信息

## 14. 命令和权限

命令根为 /zstdnet

无需权限：

- ping：仅玩家可执行的直接 TCP RTT 测量
- debug：生成诊断报告并返回文件路径

需 2级 权限：

- start、stop、reload：管理服务端 Zstd 服务
- complevel set <serverLevel> <clientLevel>：设置服务端出站等级并保存客户端出站等级目标（均为 1-22）
- benchmark start、benchmark interval <分钟>：控制 benchmark
- dictionary train <seconds>、stop、cancel：控制训练
- dictionary import <路径>、export、switch <字典名>、unload、name <文件> <字典名>：管理字典

管理命令只对专用服务器有效；字典训练、切换和卸载会影响后续连接,使用 dictionary switch 切换字典时服务端会断开现有玩家连接以避免流上下文和字典状态混用

## 15. 连接关闭和错误处理

连接关闭时：

- encoder 取消定时 flush,释放 pending ByteBuf
- encoder 和 decoder 关闭持久 Zstandard 流
- decoder 释放连接级异步状态；共享 worker 池继续供其他连接使用
- 字典会话清理下载和激活状态
- 服务端从活跃 IP 计数和统计中移除连接
- 未完成的字典下载记录失败原因

以下情况会关闭当前连接或保持 vanilla：

- 帧长度、VarInt、控制记录或协议版本非法
- 解压失败或持久流异常
- 字典压缩帧在字典激活前到达
- Accessor 或 ServerChannel 注入失败
- 半连接在握手超时内未完成协议判定

探测失败本身不会关闭真正的 Minecraft 连接,而是让该连接继续使用普通协议

## 16. UDP 和第三方网络管线

ZstdNet 只处理 Minecraft TCP。Sable 的 UDP pipeline、独立 DatagramChannel 和非 Minecraft UDP socket 都不会安装 ZstdNet 编解码器。Sable 通过 optional 依赖和运行时管线识别保持可选；ZstdNet 不会静态链接 Sable API

服务端注入器同时使用 Mixin Accessor、ServerChannel 类型筛选和 DatagramChannel 排除,避免把独立 UDP 监听器当作 Minecraft TCP 接受器。Accessor Mixin 未应用时，注入器会记录 `accessor_missing`，输出 `transport upgrade: DISABLED`，并保留原版网络

## 17. 构建

项目构建入口为：

    bash ./build.sh

常规 Gradle 回归测试为：

    ./gradlew test
