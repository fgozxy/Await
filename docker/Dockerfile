# Await 自建 WebDAV 备份盘
# 基于官方 Apache httpd + mod_dav —— WebDAV 最成熟的实现，PROPFIND / MKCOL 行为最标准
#
# 用 Debian 版而不是 alpine 版：alpine 版的 apr-util 里没有 apr_dbm 驱动，
# mod_dav_fs 打不开锁数据库，任何 MKCOL / PUT 都会返回 500（该构建也没有
# DavLockDBType 可以改用内置类型）。Debian 版自带 apr_dbm_gdbm / apr_dbm_db。
FROM httpd:2.4

LABEL org.opencontainers.image.title="Await WebDAV" \
      org.opencontainers.image.description="给 Await 倒数日应用用的自建 WebDAV 备份盘" \
      org.opencontainers.image.source="https://github.com/fgozxy/Await"

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl tzdata && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /data /var/lib/dav

COPY conf/webdav.conf /usr/local/apache2/conf/httpd.conf
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# 备份数据落在这里，用 volume 挂到宿主机目录
VOLUME ["/data"]
EXPOSE 80

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["httpd-foreground"]
