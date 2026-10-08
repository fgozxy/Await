# ⏳ Await

一款简洁优雅的 Android **倒数日**应用。支持 **软件通知、Telegram、ntfy** 三种通知渠道，可单选或同时开启多个。软件通知在手机发送，Telegram / ntfy 由服务器定时推送。

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![Kotlin](https://img.shields.io/badge/language-Kotlin-purple)

## ✨ 功能特性

### 📅 倒数日管理
- 新建 / 编辑 / 删除倒数日日程（**左滑即删，带撤销**）
- 大字号倒计时展示：**还有 N 天 / 就是今天 / 已过去 N 天**
- 日程备注、标记颜色、置顶
- 循环周期：按天 / 周 / 月 / 年，支持「每隔 N 个单位」
- 分组浏览：置顶 / 今天 / 即将到来 / 已经过去
- **独立管理分组**：可以先建好空分组再往里放日程，新建/编辑日程不再是唯一入口
- **批量分配分组**：多选或全选日程，一次性归到某个分组，或整批移出分组
- **分组批量管理**：多选 / 全选删除分组，可选择「仅移出分组」或「连同日程一起删除」，默认分组（未分组）同样可清空
- 全文搜索

### 🔔 多渠道通知
- 「通知设置」勾选软件通知 / Telegram 通知 / ntfy 通知，可任选一种或任意组合；所有日程共用渠道设置
- 软件通知无需服务器和网络，支持提前提醒、周期循环、合并通知、重启恢复；重新开机补发最近 24 小时内已安排的提醒
- Android 13+ 需允许通知权限；Android 12+ 允许精确提醒后按指定分钟调度，未授权时使用系统非精确提醒，可能延迟
- 软件通知为普通系统通知，不持续响铃，也不显示全屏提醒；手机关机或应用被强行停止时无法通知，重新打开后恢复调度
- Telegram / ntfy 由服务器发送，手机关机后仍会推送已同步日程；新增、编辑、删除、导入和渠道选择联网后自动同步
- 多级提前提醒、分钟级提醒时刻、按日 / 周 / 月 / 年循环和合并通知在各渠道中保持一致
- 服务端按渠道保存发送记录并分别重试，一个渠道失败不会重复发送另一个渠道的成功通知
- 手机配置 HTTPS 地址、加密访问密钥；Telegram Bot Token / Chat ID、ntfy 地址 / Topic / 可选 Token 留在服务器环境中，不进入手机备份
- 新安装默认软件通知；从已有 Telegram 服务端版本升级时保留 Telegram 选择，不自动多开渠道

云端通知先部署或升级 [通知服务端](server/README.md)，手机「通知设置」保存渠道并测试。仅软件通知无需部署服务器。关闭云端渠道需同步成功才会取消服务器待发提醒；离线时仍按上次同步的设置推送。旧版仅 Telegram 服务器需升级后才能同步新的渠道选择。

### 📥 导入日程（独立板块）
- **粘贴 JSON**：不必先存成文件，直接粘贴文本；外面裹的 ``` 代码块和前后多余的说明文字会被自动剥掉
- **从文件导入**：读取 Await 的备份文件，或任何符合格式的 JSON
- **AI 迁移助手**：内置一段现成提示词，一键复制后连同旧应用的截图发给 AI，把返回的 JSON 粘回来即可完成搬家
- **宽松格式**：接受裸数组、可省略 id 与大部分字段；日期直接写 `"date": "2026-10-01"`，不必换算 epoch day
- **导入方式可选**：合并（同 id 以备份为准）或覆盖（清空后导入）

### 💾 备份与恢复
- **本地导出**：一键导出为 JSON 文件（走系统文件选择器，可存到手机或网盘）
- **WebDAV 云备份**：支持坚果云、Nextcloud、群晖等任意 WebDAV 服务
- **定时自动备份**：每天 / 每 3 天 / 每周 / 每月 + 指定时刻，可限定仅 WLAN
- **云端历史版本**：按时间保留最近 N 份，随时挑一份恢复
- 错过的备份（关机等）会在下次启动时自动补做，失败结果可在「备份与恢复」查看

### 🐳 自建 WebDAV（可选）
不想把备份放在第三方网盘的，`docker/` 目录里有一套开箱即用的自建方案：一条
`docker compose --profile https up -d` 起一个 Apache `mod_dav` 服务，Caddy 自动签发
Let's Encrypt 证书，备份直接落在自己 VPS 的磁盘上。详见 [docker/README.md](docker/README.md)。

## 🛠 技术栈

| 项目 | 选型 |
|---|---|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3（支持动态取色） |
| 架构 | MVVM（ViewModel + StateFlow） |
| 存储 | Gson + SharedPreferences（轻量数据） |
| 提醒 | Android AlarmManager 软件通知；Python + SQLite 服务端 Telegram / ntfy 推送 |
| 日程同步 | HTTPS + WorkManager，断网重试 |
| 备份 | 自研极简 WebDAV 客户端（HttpURLConnection，零依赖） |

## 📦 构建

```bash
cd Await
./gradlew assembleDebug      # 调试版
./gradlew assembleRelease    # 正式版（需签名配置）
```

要求：JDK 17+，Android SDK 34。

### 🔑 正式签名配置

正式版需要签名密钥。在项目根目录创建 `keystore.properties`（已被 `.gitignore` 排除，不会提交）：

```properties
storeFile=keystore/await-release.keystore   # 密钥库路径
storePassword=你的密钥库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

生成新密钥库：

```bash
keytool -genkeypair -v -keystore keystore/await-release.keystore \
  -alias await -keyalg RSA -keysize 2048 -validity 10950
```

> ⚠️ 请妥善备份 `keystore.properties` 和密钥库文件。丢失后无法为已安装用户推送升级。

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。
