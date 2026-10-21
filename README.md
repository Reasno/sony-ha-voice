# Sony HA Voice

Android TV 专用 Home Assistant 语音客户端，包名 `dev.guxi.sonyhavoice`。通过 Home Assistant 的 `/api/websocket` 和 `assist_pipeline/run` 传输语音，使用 HA 中配置的语音识别、对话引擎和语音合成。

相关项目：[Sony TV Home](https://github.com/Reasno/sony-tv-home) · [Sony AirPlay TV](https://github.com/Reasno/sony-airplay-tv)。

电视端提供标准 Android 助手入口、透明语音面板、录音、文字显示、语音回答播放和连接管理。支持 Android 8.0 及以上、32 位 ARM 电视。默认开机启动连接服务；录音只在用户启动语音会话时开启，结束、取消、切换应用或超时后释放。服务使用 Android 要求的常驻通知。

## 使用

1. 安装 APK，打开 Sony HA Voice。
2. 填入 HA 地址与访问令牌，或使用电脑上的授权工具完成 HA OAuth 登录。
3. 保存配置，确认连接状态为“就绪”。
4. 选择“设为系统助手”，在 Android 系统中选择 Sony HA Voice。
5. 使用遥控器语音键启动会话，在界面显示“正在聆听”后说话。默认检测静音结束输入，也可按 OK 结束录音。返回键取消会话。

回答生成或播放期间，再按语音键会停止当前回答并开始下一轮；聆听期间再按语音键则结束录音并发送。连续对话使用 HA 返回的会话 ID 传递上下文，关闭面板后短时间内再次打开也可继续追问。连续 10 分钟没有完成对话或连接服务重启后，开始新的上下文；实际记忆能力由 HA 的对话引擎提供。

“按键启动会话”与“按住说、松开发送”是不同操作：系统助手入口通常在按键松开后才交给客户端。初次操作应在界面显示“正在聆听”后讲话。

语音面板浮在当前画面下方；聆听时显示音量动效，收到识别文字和回答后展开。按返回关闭面板并回到原画面，按 Home 结束会话并返回桌面。

设置中的录音设备按电视实际可用设备列出；自动选择使用 Android 标准录音路由。录音路由和音量指标记录在诊断日志中。电视内置麦克风与遥控器麦克风的识别、可用性和音频路由需按固件确认，应用不会仅凭硬件特性标记认定录音不可用。

开机启动连接服务不保证电视在深度待机时可联网、接收语音或播放声音。强行停止应用后需要再次打开。开机时不会弹出语音窗口。

实机验证设备为 Sony K-55XR50、Android 14：HA 认证、中文识别、语音回答播放、标准助手入口及开机连接服务均已验证。系统报告的录音路由为内置麦克风 `bottom`；遥控器自身麦克风的采音尚未确认。

## 构建

需要 Python 3、JDK 17 或 21，以及 Android SDK platform 34 或更新版本、build-tools 36 或更新版本。构建脚本可从 Android Studio、IntelliJ 和常见 SDK 目录发现工具；也支持显式配置：

```bash
python3 scripts/build.py --java-home /path/to/jdk --sdk /path/to/android-sdk
```

构建只编译 Java，使用 SDK 的 `javac`、D8、AAPT 和 apksigner，依赖版本固定。中间文件和调试签名密钥保存在用户缓存与状态目录；APK 输出到 `dist/sony-ha-voice-debug.apk`。

## ADB 安装与配置

```bash
TV_ADB='<TV_IP>:<DEBUG_PORT>'
python3 scripts/tv.py --serial "$TV_ADB" install
python3 scripts/tv.py --serial "$TV_ADB" status
python3 scripts/tv.py --serial "$TV_ADB" set-assistant
python3 scripts/tv.py --serial "$TV_ADB" restore-assistant
```

使用电视无线调试页面显示的实际地址和端口。部分固件重启后需要重新打开无线调试；语音客户端运行不依赖 ADB。

安装不会卸载其他应用。切换助手前会按电视序列号记录助手角色与三个语音设置，恢复命令使用对应电视的备份，端口变化不影响备份定位。

`set-assistant` 会通过 ADB 授予 `WRITE_SECURE_SETTINGS` 权限，用于保持系统语音入口与已选择的助手角色一致。Android 10 及以上只有在 Sony HA Voice 仍持有默认助手角色时才维护绑定；改选其他助手后停止维护。`restore-assistant` 会撤销该权限并恢复备份中的绑定。

访问令牌可从 HA 用户资料的安全设置创建。配置文件在电脑端应设置为仅本人可读：

```json
{
  "url": "http://homeassistant.local:8123",
  "token": "<HA access token>",
  "pipeline": "",
  "device_id": "",
  "input_device": -1,
  "audio_source": 1,
  "autostart": true
}
```

`pipeline` 留空使用 HA 首选语音流程；`input_device=-1` 使用自动路由；`audio_source=1` 是标准麦克风。凭据通过 ADB 标准输入写入应用私有目录，再由应用用 Android Keystore 加密保存，不放入命令参数、日志或 APK。

```bash
python3 scripts/tv.py --serial "$TV_ADB" configure --file /private/path/config.json
```

电脑 OAuth 授权工具会打开 HA 授权页面，保存客户端专用的访问与刷新令牌，再配置电视。使用独立 OAuth 会话，不读取其他应用的登录数据：

```bash
python3 scripts/authorize.py --serial "$TV_ADB" --ha-url http://homeassistant.local:8123
```

## 通信与运行边界

- 桌面通过 `content://dev.guxi.sonyhavoice.status/connection` 查询连接可用性并订阅变化。接口仅提供 `ready` 字段，不提供凭据、录音和对话内容；调用方必须持有签名权限 `dev.guxi.sonyhavoice.READ_STATUS`，并与助手使用相同签名。
- 桌面通过显式 `ACTION_ASSIST` 启动 `dev.guxi.sonyhavoice/.VoiceActivity`；设置入口为 `.MainActivity`。
- WebSocket 使用 HA 标准认证消息，按请求 ID 关联事件。
- 从 `intent-end` 的 `intent_output.conversation_id` 保存会话标识，下一轮作为 `assist_pipeline/run` 的顶层 `conversation_id` 传入；标识仅保存在连接服务内存中。
- 语音使用 16 kHz、单声道、16 位小端 PCM；二进制帧以前置处理器 ID 标识会话，结束帧只包含该 ID。
- 客户端检测静音结束录音，最长录音 20 秒，无有效声音时 8 秒结束。服务端音量放大为 4 倍；HA 自带的 VAD 关闭，输入由客户端结束帧控制。
- 开始录音后使用短缓冲保存初始化期间的音频，服务端准备完成后发送；缓冲有上限，超时失败时丢弃。
- 接收 STT、intent、TTS 和错误事件。回答音频由电视播放器获取并播放；凭据只发送给配置的 HA origin。
- 会话超时、网络中断、用户取消和录音错误都会结束录音并释放播放器。新一轮丢弃上一轮的待处理事件和播放器回调；不会在重连时重放语音或重复执行指令。停止客户端播放不会撤销 HA 已执行的设备操作。
- 地址与令牌、原始录音不记录到日志；音量与路由指标不包含原始声音。
- 连接服务使用 `specialUse` 类型的 Android 前台服务，默认在开机后启动并保持连接；语音录音由用户可见语音页面启动。
- 局域网 HTTP 地址使用未加密传输；部署在不可信网络时应配置 HTTPS。

## 验证

```bash
python3 -m unittest discover -s tests
```

单元验证执行实际 Java PCM 编码与录音结束判定，覆盖小端样本、二进制处理器 ID、静音超时、低音量语音与最长录音。

HA 通信验证需要 `websocket-client`，使用查询时间的请求验证认证、首选语音流程、对话与回答音频：

```bash
python3 -m pip install websocket-client
python3 scripts/verify_ha.py
```

实机验收应分别检查默认助手绑定、真实语音键、录音来源、中文识别、回答播放与重启后的服务状态。

## 许可证

项目源码使用 [MIT 许可证](LICENSE)。OkHttp、Okio、Kotlin 等构建依赖保留各自许可证。
