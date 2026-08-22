# Await 自建 WebDAV 备份盘

用一台 VPS（或家里的 NAS / 小主机）的本地磁盘，跑一个专给 Await 用的 WebDAV 服务，
让「备份与恢复」里的云备份存到自己手里，不依赖第三方网盘。

基于官方 Apache `httpd` + `mod_dav`，镜像里只加载必需模块；备份文件就是宿主机上的普通
JSON 文件，随时可以 `cp`、`rsync` 再备一份。

---

## ⚠️ 开始之前：Android 不允许明文 HTTP

Await 面向 Android 8+ 构建（`targetSdk 34`），系统默认禁止明文 HTTP 流量。
**用 `http://` 填服务器地址会直接失败**，报错类似：

```
CLEARTEXT communication to 1.2.3.4 not permitted by network security policy
```

所以你需要 HTTPS。本项目提供两种做法：

| 场景 | 做法 |
|---|---|
| **有域名**（推荐） | 用下面的 `https` 方案，Caddy 自动申请并续期 Let's Encrypt 证书 |
| 已有 nginx / 宝塔 / Traefik | 只启 WebDAV 容器，用你现成的反向代理套 TLS，转发到 `127.0.0.1:8080` |
| 只有 IP、没有域名 | 证书签不出来。可以申请一个免费域名，或把服务放在 WireGuard / Tailscale 内网里用 |

---

## 🚀 快速开始（有域名）

前提：域名 A 记录已指向这台机器，`80` / `443` 端口对公网开放。

```bash
git clone https://github.com/fgozxy/Await.git
cd Await/docker

cp .env.example .env
# 生成强密码
openssl rand -base64 24
vi .env          # 填 WEBDAV_USER / WEBDAV_PASSWORD / AWAIT_DOMAIN

# 只让 Caddy 能访问 WebDAV，明文端口不暴露到公网
echo 'WEBDAV_BIND=127.0.0.1' >> .env

docker compose --profile https up -d
docker compose logs -f caddy      # 看证书是否签发成功
```

然后在手机上打开 **Await → ⋮ → 备份与恢复**：

| 输入项 | 填什么 |
|---|---|
| 服务器地址 | `https://你的域名/`（例：`https://dav.example.com/`） |
| 账号 | `.env` 里的 `WEBDAV_USER` |
| 密码 | `.env` 里的 `WEBDAV_PASSWORD` |
| 备份目录 | `Await`（留默认即可，不存在会自动创建） |

点「测试连接」，看到「连接正常」就成了；再点「立即备份」验证一次，然后按需打开定时备份。

## 🚀 快速开始（用你现成的反向代理）

```bash
cp .env.example .env && vi .env    # 填账号密码，AWAIT_DOMAIN 可以不管
echo 'WEBDAV_BIND=127.0.0.1' >> .env
docker compose up -d               # 只起 WebDAV，监听 127.0.0.1:8080
```

在你的反代里把某个 HTTPS 站点转发到 `127.0.0.1:8080` 即可。nginx 示例：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    # 备份文件很小，给个宽松值就行
    client_max_body_size 64m;
}
```

> 注意别在反代层再加一层 Basic Auth，Await 只会发送一组凭证。

---

## ⚙️ 配置项（.env）

| 变量 | 默认 | 说明 |
|---|---|---|
| `WEBDAV_USER` | — | **必填**，登录账号 |
| `WEBDAV_PASSWORD` | — | **必填**，强密码；`changeme` 之类的示例值会被拒绝启动 |
| `AWAIT_DOMAIN` | — | 用 `https` 方案时必填，已解析到本机的域名 |
| `AWAIT_TLS_EMAIL` | 空 | Let's Encrypt 到期提醒邮箱，可留空 |
| `WEBDAV_DATA` | `./data` | 备份在**宿主机**上的存放目录 |
| `WEBDAV_PORT` | `8080` | 容器映射到宿主机的端口 |
| `WEBDAV_BIND` | `0.0.0.0` | 监听地址；套了反代就改成 `127.0.0.1` |
| `PUID` / `PGID` | `1000` | 备份文件在宿主机上的属主，**不能为 0** |
| `TZ` | `Asia/Shanghai` | 时区，影响日志时间 |

改完 `.env` 后 `docker compose up -d` 重建生效。**改密码只需改 `.env` 再重启容器**，
账号文件每次启动都会按环境变量重建。

---

## 📁 备份文件长什么样

```
data/
└── Await/
    ├── Await-backup-20260822-220000.json   ← 带时间戳的历史快照，按「保留份数」自动清理
    ├── Await-backup-20260823-220000.json
    └── Await-backup-latest.json            ← 固定名的最新副本
```

就是普通 JSON，直接 `cat` 能看，也能拷回电脑用 Await 的「从文件导入」恢复。
浏览器打开 `https://你的域名/Await/` 输入账号密码，同样能看到列表并手动下载。

再加一层保险（宿主机定时另存一份）：

```bash
0 3 * * * tar czf /backup/await-$(date +\%F).tar.gz -C /path/to/Await/docker/data .
```

---

## 🔧 常见问题

**测试连接报「账号或密码不正确」**
`.env` 改完后要 `docker compose up -d` 重启容器才生效；密码里有 `$`、`#`、空格时用引号包起来。

**报「备份目录不存在，请先测试连接或在网盘中手动创建」**
点一次「测试连接」，容器会自动创建该目录；也可以直接 `mkdir -p data/Await`。

**上传报 403**
宿主机数据目录属主和 `PUID`/`PGID` 对不上：`sudo chown -R 1000:1000 ./data`。

**容器起不来，日志说 `PUID/PGID 不能为 0`**
Apache 拒绝以 root 提供服务。保持默认 `1000` 即可，宿主机上用 root 照样能读写这些文件。

**手机上报 `CLEARTEXT ... not permitted`**
你填的是 `http://`。见文首说明，必须走 HTTPS。

**Caddy 签不出证书**
确认域名已解析到本机、`80` 端口没被别的服务占用（宝塔/nginx 常年占用），
且云厂商安全组放行了 `80` 和 `443`。看日志：`docker compose logs caddy`。

**能用别的 WebDAV 客户端连吗**
可以。Apache `mod_dav` 是完整实现（含 LOCK），Windows 映射网络驱动器、RaiDrive、
Cyberduck、各类文件管理器都能连同一个地址。

---

## 🔒 安全建议

- 密码用 `openssl rand -base64 24` 生成，别复用其他站点的
- 套了反代就把 `WEBDAV_BIND` 设成 `127.0.0.1`，别让明文端口暴露到公网
- 备份 JSON 里是你的全部日程内容（标题、备注、日期），当作隐私数据对待
- 建议给 VPS 装个 `fail2ban` 盯着 Apache 的认证失败日志
- 想更省事也可以完全不自建，Await 同样支持坚果云等公有 WebDAV 服务

---

## ✅ 这套配置验证过什么

镜像构建后用 **Await 应用自身的 `WebDavClient` 代码**（不是 curl 模拟）跑通了完整链路：
目录自动创建、上传快照与最新副本、覆盖同名文件、含中文与空格的文件名、`PROPFIND` 列目录
与排序、下载、删除（保留份数清理用）、错误密码识别、未认证访问被拒。
