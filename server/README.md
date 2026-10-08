# Await 云端服务

手机同步日程后，由服务器按手机的 IANA 时区定时向所选的 Telegram / ntfy 渠道推送。手机关机不影响已经同步的日程；离线新增、修改、删除的日程需在手机联网同步成功后生效。

Python 3.12 实现，SQLite 持久化日程、发送队列和可选云端备份，`cryptography` 提供渠道凭据和备份加密。支持独立渠道选择、分别重试、当天 / 提前多天提醒、日 / 周 / 月 / 年循环；同一天、同一时刻的日程也逐条推送。月末和闰日以原始日期为锚点，不逐月漂移。提醒周期由服务器继续计算，不依赖手机持续上传。

## 部署

初次部署只需从凭据库向进程环境注入 `AWAIT_API_KEY`，无需预先配置 Telegram 或 ntfy。消息渠道可在手机验证云端连接后配置；也兼容原有环境变量配置。不把凭据明文写入文件或命令参数：

| 环境变量 | 用途 |
|---|---|
| `AWAIT_BACKUP_ENABLED` | 是否开启云端备份，默认 `false`；支持 `true` / `false` / `1` / `0` |
| `AWAIT_API_KEY` | 手机访问密钥，至少 32 位 ASCII，不含空白 |
| `TELEGRAM_BOT_TOKEN` | 启用 Telegram 时配置：BotFather 创建的机器人 Token |
| `TELEGRAM_CHAT_ID` | 启用 Telegram 时配置：收件人的数字 Chat ID，或群 / 频道的 `@username` |
| `NTFY_URL` | 启用 ntfy 时配置：服务的 HTTPS 根地址，如 `https://ntfy.sh` 或自建服务地址 |
| `NTFY_TOPIC` | 启用 ntfy 时配置：订阅主题，1–64 位字母 / 数字 / 下划线 / 连字符 |
| `NTFY_TOKEN` | ntfy 可选访问 Token，通过 Bearer 认证发布 |
| `AWAIT_SERVER_PORT` | 宿主机端口，默认 8091，仅绑定 127.0.0.1 |

Telegram 收件人需先向机器人发送 `/start`；推送到群或频道时，机器人须已加入并具备发消息权限。

```bash
cd server
docker compose up -d --build
```

用现有反向代理提供 HTTPS，并转发到 `127.0.0.1:8091`。手机「云端部署配置」填写 Await 的 HTTPS 地址和访问密钥，点击「验证连接并保存」。验证通过后，进入「通知设置」的「消息渠道」，填写 Telegram 或 ntfy 参数，测试填写的参数、保存到云端，再勾选渠道并点击「保存通知设置」。软件通知独立在手机发送，无需服务器；测试按钮会分别显示 Telegram / ntfy 的结果。手机上的访问密钥使用 Android Keystore 加密，不进入日程备份或系统备份。手机提交的 Token 在服务器 SQLite `channel_settings` 表中以 AES-256-GCM 加密保存；加密密钥通过 HKDF 从 `AWAIT_API_KEY` 派生，随机 nonce 与密文一同存储。读取配置只返回状态、Chat ID、ntfy 地址和 Topic，不返回 Token。手机不持久化云端渠道 Token。

仅启用 ntfy 时无需配置 Telegram 环境变量。使用环境变量时，Telegram 两个字段必须一起提供，ntfy 的地址与主题必须一起提供；手机选中的云端渠道必须已在服务器配置。ntfy 客户端需订阅相同的服务器和 Topic。发布协议参见 [ntfy 官方文档](https://docs.ntfy.sh/publish/#publish-as-json)，Telegram 使用 [sendMessage](https://core.telegram.org/bots/api#sendmessage)。

手机保存的渠道配置会覆盖同渠道的环境变量配置，重启后继续生效；Token 留空保留已有值，ntfy 可显式选择清除 Token。测试填写的参数不会修改正式配置或自动开启渠道。修改参数后无需重启服务，最近 24 小时内该渠道失败的提醒会重新尝试，成功记录保持不变。

`AWAIT_API_KEY` 同时用于访问认证和配置加密，须与数据卷一起妥善保管。更换该密钥前需要迁移已有加密配置；直接替换后服务器会拒绝读取旧密文，不会回退到旧环境配置或覆盖密文。

升级可沿用原有 `telegram` Compose 服务与 `schedule_data` 卷：重新构建容器即可，服务名保留以避免创建第二个调度实例。首次启动会自动给旧发送队列增加渠道字段，保留 Telegram 已发送记录；旧手机未上传渠道字段时仍默认 Telegram。新手机只开启软件通知时会上传空的云端渠道列表，取消待发云端消息。

v2.3.1 移除通知合并功能。旧快照与旧手机请求中的 `mergeGroups` 字段会被忽略，同一天、同一时刻也按日程分别生成任务。启动时自动拆分旧合并队列，保留各渠道的成功记录、失败状态与重试时间；已成功的合并消息不会因拆分重新发送，已删除或修改的旧成员会取消。迁移保留手机绑定、同步版本和原同步时间，不要求手机先上传新快照。新手机继续发送空的 `mergeGroups` 以兼容尚未升级的服务器；云端服务升级后，旧手机也会逐条推送。

服务端 SQLite 位于 Docker 的 `schedule_data` 卷，容器重建会保留。备份此卷时停止服务或使用 SQLite backup API，避免复制正在写入的单个数据库文件。

## 可选云端备份

在部署时选择开启，访问密钥仍通过凭据库注入进程环境：

```bash
cd server
AWAIT_BACKUP_ENABLED=true docker compose up -d --build
```

手机「云端部署配置」的部署指引也提供相同开关和可复制的命令。该选项只生成命令，需在服务器执行，再点击手机的「验证连接并保存」。验证后在「备份与恢复」立即上传、查看历史或恢复最新备份，无需另配账号、路径或密码。不需要开启 Telegram / ntfy，也可单独使用云端备份。

此开关在创建 / 重建容器时从进程环境读取；后续重建时继续提供 `AWAIT_BACKUP_ENABLED=true`，或在部署工具中保存这个非敏感设置。设为 `false` 重建后接口关闭，原有历史保留，重新开启后可继续恢复。容器普通重启沿用已保存的环境变量。

备份内容使用手机 JSON 导出格式，包含所有日程字段、显式空分组，不含云端连接或消息渠道密钥。新备份的兼容字段 `mergeGroups` 始终为空，手机恢复旧备份时也忽略旧通知合并设置。每次上传写入独立历史版本，并在同一 SQLite 事务内按手机设置保留最近 1–100 份（手机提供 5 / 10 / 20 / 50 份）；改变保留份数在下次成功备份时生效。历史按服务器接收时间排序，文件名含 UTC 时间和唯一编号，手机显示本地时间。最新备份直接读取最新历史，不另写一个可能失败的副本。

数据库 `cloud_backups` 表仅存加密内容与索引元数据，使用随机 nonce 和独立 HKDF 派生的 AES-256-GCM 密钥。访问密钥需与数据卷一起保管；错误密钥或被篡改的密文不会静默恢复为空备份。备份功能与提醒队列独立，上传、读取或恢复文件不会更改服务器通知计划；手机确认导入后才进行正常日程同步。

定时备份由手机 AlarmManager 触发、WorkManager 在满足网络条件时上传完整本地数据；服务器不会凭通知快照生成缺少分组、颜色等信息的备份。手机关机时不产生新上传，重新启动后补做已逾期的备份。开启云端存储不代表开启手机定时任务，需在手机另行选择周期与时刻。

同一实例只允许绑定手机上传。持有相同访问密钥的新手机可验证连接并恢复历史，但不能覆盖旧日程或上传新备份，需先完成绑定迁移并重新验证。若先上传备份、尚未同步日程，也会记录手机绑定。旧 WebDAV 部署和文件不会被删除；下载旧 JSON 后用「导入日程 → 从文件导入」恢复，再上传到 Await 云端。旧定时开关与明文 WebDAV 凭据在手机升级后清除，定时偏好保留但需重新开启。

## 送达和迁移

- 网络失败、各渠道的 429 / 5xx 会退避重试；重启后恢复待发提醒，并补发过去 24 小时内未送达的提醒。更旧的提醒标记失败，不批量补发。
- 云端渠道永久拒绝（例如 Telegram Token / Chat ID 或 ntfy 权限错误）会标记失败。手机「查看服务器状态」显示失败数量和最近错误，修正服务端凭据后新的提醒继续发送。
- 每个渠道单独记录成功状态；一个渠道失败不会重复推送另一渠道已经成功的提醒。已成功的提醒不会因正常重启或重复同步再次发送。远端发布接口不提供此队列使用的幂等键，发送成功但连接丢失，或发送后进程立即崩溃时，重试可能重复送达。
- 关闭渠道会删除它尚未发送的提醒，保留成功记录；重新开启只恢复当前和未来待发提醒。关闭全部云端渠道后服务器继续保存日程，但不生成发送任务。
- 日程修改 / 删除会取消相应未发送的提醒。普通日程分组仅用于浏览，不影响每条日程独立通知。
- 一个服务端实例绑定一个手机安装 ID。另一台手机会收到 409，避免误覆盖已有日程。迁移时先备份数据库与手机日程，停止服务，修改 SQLite `snapshot.body` 的 `clientId` 和 `backup_owner.client_id`（若存在）为新手机 ID，并使新手机的同步版本高于旧版本，然后再同步。不要删除备份；同一个手机的调试版和正式版也是不同安装。
- 更换服务器后应停用旧服务器，否则旧服务器仍会按其保存的日程推送。

## API

除了 `/healthz`，均需 `Authorization: Bearer` 鉴权。一个实例只接受一个日程快照；相同版本、相同内容可重复提交，旧版本或相同版本不同内容会被拒绝。请求体上限 4 MiB。

| 方法 | 路径 | 用途 |
|---|---|---|
| PUT | `/v1/schedule` | 原子替换手机日程快照与 `notificationChannels`（`telegram` / `ntfy`，可为空） |
| GET | `/v1/status` | 日程数量、队列状态、最近错误、`notificationProtocol: 1`、`availableChannels`、`channelConfiguration`、`backupEnabled` / `backupProtocol` |
| GET | `/v1/channels` | 渠道就绪状态、配置来源与非密钥参数，不返回 Token |
| PUT | `/v1/channels/telegram` | 加密保存 `botToken` / `chatId`；Token 留空保留已有值 |
| PUT | `/v1/channels/ntfy` | 加密保存 `url` / `topic` / 可选 `token`；`clearToken: true` 清除 Token |
| POST | `/v1/channels/{telegram,ntfy}/test` | 测试填写的参数，不保存，不修改正式配置 |
| POST | `/v1/test` | 请求体可指定 `notificationChannels`；逐渠道发送测试并返回 `results` / `ok`，不会改动正式渠道选择 |
| POST | `/v1/backups` | 开启备份时接受 `{clientId, keepCount, backup}`，`backup` 为完整 Await JSON 导出对象；返回历史编号与元数据 |
| GET | `/v1/backups` | 开启备份时返回 `backups` 数组，按新到旧排列，含 `id` / `name` / `createdAt`（毫秒）/ `eventCount` / `size` |
| GET | `/v1/backups/{id}` | 下载指定备份的 JSON 导出对象 |
| GET | `/v1/backups/latest` | 下载最新备份；无历史返回 404 |
| GET | `/healthz` | 进程健康检查 |

验证：

```bash
python3 -m pip install -r server/requirements.txt
python3 -m unittest discover -s server -v
```

测试使用临时 SQLite、回环 HTTP 和假的 Telegram / ntfy 响应，不发送真实消息。
