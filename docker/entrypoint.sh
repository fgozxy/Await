#!/bin/sh
# Await WebDAV 启动脚本：按环境变量生成账号、对齐属主、校验配置
set -eu

WEBDAV_USER="${WEBDAV_USER:-}"
WEBDAV_PASSWORD="${WEBDAV_PASSWORD:-}"
PUID="${PUID:-1000}"
PGID="${PGID:-1000}"
HTPASSWD_FILE=/usr/local/apache2/conf/.htpasswd

if [ -z "$WEBDAV_USER" ] || [ -z "$WEBDAV_PASSWORD" ]; then
    echo "[await-webdav] 错误：必须设置 WEBDAV_USER 和 WEBDAV_PASSWORD（见 .env）" >&2
    exit 1
fi
case "$WEBDAV_PASSWORD" in
    changeme|password|123456|admin)
        echo "[await-webdav] 错误：请把 WEBDAV_PASSWORD 换成真正的强密码，别用示例值" >&2
        exit 1 ;;
esac

# ── 账号：每次启动按环境变量重建，改密码只需重启容器 ──
# -B = bcrypt，-c 覆盖重建，-b 从命令行取密码
htpasswd -cbB "$HTPASSWD_FILE" "$WEBDAV_USER" "$WEBDAV_PASSWORD" >/dev/null 2>&1
chmod 600 "$HTPASSWD_FILE"

# ── 运行身份：默认 1000:1000，让宿主机上的备份文件属主正常，不是 root ──
# Apache 拒绝以 root 身份提供服务（已知竞态可导致本地任意文件读取），提前拦下并说清楚
if [ "$PUID" = "0" ] || [ "$PGID" = "0" ]; then
    echo "[await-webdav] 错误：PUID/PGID 不能为 0。Apache 不允许以 root 运行。" >&2
    echo "[await-webdav] 直接用默认的 PUID=1000 PGID=1000 即可——容器会把 /data 的属主改成它，" >&2
    echo "[await-webdav] 宿主机上以 root 登录同样能读写这些备份文件。" >&2
    exit 1
fi
GROUP_NAME="$(getent group "$PGID" 2>/dev/null | cut -d: -f1 || true)"
if [ -z "$GROUP_NAME" ]; then
    groupadd -g "$PGID" webdav
    GROUP_NAME=webdav
fi
USER_NAME="$(getent passwd "$PUID" 2>/dev/null | cut -d: -f1 || true)"
if [ -z "$USER_NAME" ]; then
    useradd -M -N -u "$PUID" -g "$PGID" -s /usr/sbin/nologin webdav
    USER_NAME=webdav
fi
sed -i "s/^User .*/User ${USER_NAME}/; s/^Group .*/Group ${GROUP_NAME}/" \
    /usr/local/apache2/conf/httpd.conf

chown "$PUID:$PGID" "$HTPASSWD_FILE"
chown -R "$PUID:$PGID" /var/lib/dav
# /data 可能挂了很大的目录，只改顶层，避免每次启动递归遍历
chown "$PUID:$PGID" /data 2>/dev/null || \
    echo "[await-webdav] 提示：/data 属主未能修改，若上传报 403 请在宿主机执行 chown -R ${PUID}:${PGID} 该目录" >&2

httpd -t
echo "[await-webdav] 就绪：账号 ${WEBDAV_USER}，数据目录 /data，运行身份 ${USER_NAME}(${PUID}:${PGID})"

exec "$@"
