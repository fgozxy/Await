# ⏳ Await

一款简洁优雅的 Android **倒数日**应用。手机管理日程，服务器定时向 **Telegram** 推送；手机关机后也能收到已经同步的提醒。

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

### 🔔 Telegram 云端提醒
- 日程提醒改为服务器向 Telegram 推送，移除应用本地通知、持续响铃、震动和全屏提醒
- 「Telegram 服务器」设置 HTTPS 地址、访问密钥，支持测试推送和查看服务端状态
- 新增、编辑、删除、导入和合并推送设置自动同步；断网时排队重试，首页显示待同步状态
- 保留多级提前提醒、分钟级推送时刻、按日 / 周 / 月 / 年循环和合并推送
- 服务端持久化日程及发送队列，支持重启恢复、网络失败重试和最近 24 小时补发
- Bot Token / Chat ID 仅在服务器配置；手机访问密钥使用 Android Keystore 加密，不进入备份
- 手机不再申请日程提醒相关的通知、精确闹钟、全屏或电池优化权限

**先部署 [Telegram 服务端](server/README.md)，再在手机保存配置并同步。** 未配置时仍可管理倒数日；提醒全部依赖服务端。离线修改需同步成功才会生效。

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
| 提醒 | Python + SQLite 服务端调度，Telegram Bot API 推送 |
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
