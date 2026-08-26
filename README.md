# ⏳ Await

一款简洁优雅的 Android **倒数日**应用。记录重要的日子，让每一次等待都被温柔提醒。

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
- **分组批量管理**：多选 / 全选删除分组，可选择「仅移出分组」或「连同日程一起删除」，默认分组（未分组）同样可清空
- 全文搜索

### 🔔 强大的通知功能
- **闹钟式提醒**：到点持续响铃 + 震动，不手动关闭就一直响；走闹钟音量通道，静音模式下照响
- **稍后提醒**：全屏提醒页与通知栏都有「稍后提醒」，10 分钟后再响一次
- **自动静音**：无人理会时 5 分钟后自动停止响铃，只留一条普通通知，不会响一整天也不耗电
- **逐条开关**：每条日程可单独决定用闹钟式提醒还是普通通知
- **手表可同步**：提醒通知与前台服务的常驻通知分开发送，手表 / 手环的通知转发能正常收到
- **多级提前提醒**：当天、提前 1 / 3 / 7 / 15 / 30 天自由组合（多选）
- **自定义提醒时刻**：精确到分钟的触发时间
- **精确闹钟**：闹钟式日程用 `AlarmManager.setAlarmClock`（完全豁免低电耗模式），普通提醒用 `setExactAndAllowWhileIdle`
- **开机自动恢复**：重启后所有提醒自动重新调度
- **升级自动恢复**：应用更新后提醒不丢失
- **独立通知渠道**：「日程提醒」「每日汇总」可分别管理重要级别
- 点击通知直达应用

### 💾 备份与恢复
- **本地导入导出**：一键导出为 JSON 文件（走系统文件选择器，可存到手机或网盘），换机后原样导入
- **导入方式可选**：合并（同 id 以备份为准）或覆盖（清空后导入）
- **宽松导入**：接受裸数组、可省略 id 与大部分字段；日期可直接写 `"date": "2026-10-01"`，不必换算 epoch day——方便从别的倒数日应用手工或借助 AI 迁移数据
- **AI 迁移助手**：备份页内置一段现成提示词，一键复制后连同旧应用的截图发给 AI，把返回的 JSON 存成文件导入即可完成搬家
- **WebDAV 云备份**：支持坚果云、Nextcloud、群晖等任意 WebDAV 服务
- **定时自动备份**：每天 / 每 3 天 / 每周 / 每月 + 指定时刻，可限定仅 WLAN
- **云端历史版本**：按时间保留最近 N 份，随时挑一份恢复
- 错过的备份（关机等）会在下次启动时自动补做，失败会有低打扰通知

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
| 提醒 | AlarmManager 精确闹钟 + BroadcastReceiver + 前台服务响铃 |
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
