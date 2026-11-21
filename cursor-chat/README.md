# Cursor Chat

Android 聊天客户端，用你自己的 Cursor API Key 连接 [Cloud Agents](https://cursor.com/docs/cloud-agent/api/endpoints)。

它参照 SillyClient 的分层，但没有在手机里跑编辑器或 Node：

| SillyClient | Cursor Chat |
| --- | --- |
| 控制台里的实例卡片 | Cloud Agent 列表 |
| 酒馆 WebView | 原生对话页 |
| 远程 Basic Auth 放进 Android 密钥库 | API Key 放进 Android 密钥库 |
| 返回控制台不停止实例 | 离开对话不取消云端运行，停止要显式点 |

对话、运行和实时流走 Cloud Agents API v1。完整历史仍读取 v0 的 `GET /v0/agents/{id}/conversation`，因为 v1 没有等价的记录接口。

## 构建

与仓库其余部分相同，需要 JDK 17 和 Android SDK 37。

```bash
./gradlew :cursor-chat:testDebugUnitTest :cursor-chat:lintDebug :cursor-chat:assembleDebug
```

调试包在 `cursor-chat/build/outputs/apk/debug/`。安装：

```bash
adb install -r cursor-chat/build/outputs/apk/debug/cursor-chat-debug.apk
```

在 Cursor Dashboard → API Keys 创建密钥后粘贴到应用里。密钥只保存在本机密钥库，不写入备份。
