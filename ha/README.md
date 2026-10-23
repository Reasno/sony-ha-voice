# HA 对话附图入口

此扩展为已安装的 `local_openai` 自定义集成提供经过 HA WebSocket 认证的图片入口。图片与问题交给语音流程配置的同一个对话实体，使用同一个 `conversation_id`、HA 会话历史和工具权限。HA 的语音识别与语音合成仍由配置的 Assist 流程执行。

## 安装

需要 Home Assistant 2026.10、使用 Responses API 的 `local_openai` 集成，以及支持图片输入的对话模型。扩展使用 HA 提供的 `probatio` 和 Pillow。

将本目录复制到 HA 主机，在主机上运行：

```bash
python3 /path/to/ha/install.py /config/custom_components/local_openai --check
python3 /path/to/ha/install.py /config/custom_components/local_openai
```

`component_dir` 使用当前执行环境中的实际路径。容器外执行时应使用宿主机上的配置目录。安装脚本先校验全部源文件，添加入口注册、用户消息附图和 Responses 图片转换三个挂接点，再复制扩展模块；已有挂接点不会重复添加。源代码不符合预期时会停止安装。

脚本将原始文件备份到组件目录旁的 `.sony-ha-voice-backups/`，随后提示备份位置。完成后重启 Home Assistant 进程，使 Python 模块生效。在 HA 的首选语音流程或客户端指定流程中，选择支持图片的 Local OpenAI 对话实体。

## 接口

经过 HA 标准 WebSocket 认证后发送：

```json
{
  "id": 10,
  "type": "local_openai/conversation/process",
  "agent_id": "conversation.your_local_openai_agent",
  "text": "画面里有哪些应用？",
  "image": "<PNG 或 JPEG 文件的标准 Base64，不含 data URI 前缀>",
  "conversation_id": "<HA 返回的会话 ID>",
  "language": "zh-CN"
}
```

`agent_id`、`text`、`image` 必填；`conversation_id`、`language`、`device_id` 可选。首次请求可省略 `conversation_id`，后续使用响应中的 ID。响应采用 HA 标准 `ConversationResult` 格式，包含 `response` 与 `conversation_id`。

图片限制为 2 MB、8,294,400 像素，接收后去除文件元数据，最长边缩至 1280 像素并转为 JPEG。问题上限为 16,000 字符，对话处理最长 90 秒。图片只保存在 HA 当前会话内存中，会话清理后释放；诊断序列化只包含附件类型。模型服务会按其自身的数据策略处理图片。

关闭连接会取消正在处理的附图请求。错误包括 `unsupported_agent`、`invalid_text`、`invalid_image` 和 `conversation_timeout`。模型不支持图片、网络错误及工具调用错误由 HA 对话实体返回。
