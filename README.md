# ⏳ Await

一款简洁优雅的 Android **倒数日**应用。记录重要的日子，让每一次等待都被温柔提醒。

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![Kotlin](https://img.shields.io/badge/language-Kotlin-purple)

## ✨ 功能特性

### 📅 倒数日管理
- 新建 / 编辑 / 删除倒数日日程
- 大字号倒计时展示：**还有 N 天 / 就是今天 / 已过去 N 天**
- 日程备注、标记颜色、置顶
- 支持每年重复（生日、纪念日、周年）
- 分组浏览：置顶 / 今天 / 即将到来 / 已经过去
- 全文搜索

### 🔔 强大的通知功能
- **多级提前提醒**：当天、提前 1 / 3 / 7 / 15 / 30 天自由组合（多选）
- **自定义提醒时刻**：精确到分钟的触发时间
- **精确闹钟**：基于 `AlarmManager.setExactAndAllowWhileIdle`，低电耗模式下依然准时
- **开机自动恢复**：重启后所有提醒自动重新调度
- **升级自动恢复**：应用更新后提醒不丢失
- **独立通知渠道**：「日程提醒」「每日汇总」可分别管理重要级别
- 点击通知直达应用

## 🛠 技术栈

| 项目 | 选型 |
|---|---|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3（支持动态取色） |
| 架构 | MVVM（ViewModel + StateFlow） |
| 存储 | Gson + SharedPreferences（轻量数据） |
| 提醒 | AlarmManager 精确闹钟 + BroadcastReceiver |

## 📦 构建

```bash
git clone https://github.com/fgozxy/Await.git
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
