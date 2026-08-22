package io.github.fgozxy.await.backup

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** WebDAV 连接配置 */
data class WebDavConfig(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val dir: String = "Await"
) {
    val isValid: Boolean
        get() = url.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    /** 规范化后的根地址，保证以 / 结尾；缺协议头时补 https */
    fun rootUrl(): String {
        var u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        return if (u.endsWith("/")) u else "$u/"
    }

    /** 备份目录地址，保证以 / 结尾；目录名为空时即根地址 */
    fun dirUrl(): String {
        val d = dir.trim().trim('/')
        return if (d.isBlank()) rootUrl() else rootUrl() + WebDavClient.encodePath(d) + "/"
    }

    fun fileUrl(name: String): String = dirUrl() + WebDavClient.encodePath(name)

    fun authHeader(): String = "Basic " + Base64.encodeToString(
        "$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
    )
}

/**
 * 极简 WebDAV 客户端，只依赖 HttpURLConnection —— Android 上它由 OkHttp 承载，
 * 原生放行 PROPFIND / MKCOL 等 WebDAV 扩展方法，无需额外依赖。
 *
 * 能力：PUT 上传、GET 下载、DELETE 删除、MKCOL 建目录、PROPFIND 列目录。
 * 全部为阻塞调用，调用方负责切到 IO 线程。
 */
object WebDavClient {

    private val HTTP_DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.US)

    /** 备份快照的文件名格式：Await-backup-20260822-100000.json */
    private val NAME_STAMP = Regex("Await-backup-(\\d{8}-\\d{6})\\.json", RegexOption.IGNORE_CASE)
    private val NAME_STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    /** 目录中的一个条目 */
    data class Entry(val name: String, val lastModified: String, val size: Long) {
        fun sizeText(): String = when {
            size <= 0 -> ""
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
            else -> "%.1f MB".format(size / 1024.0 / 1024.0)
        }

        /**
         * getlastmodified 是 RFC 1123 格式（如 `Sat, 22 Aug 2026 10:00:00 GMT`）。
         * 这里先把星期前缀去掉再解析：标准的 RFC_1123_DATE_TIME 会校验星期与日期是否自洽，
         * 而部分服务器给的星期是错的，严格解析会整条失败。解析不了返回 0。
         */
        fun modifiedAtMillis(): Long {
            val raw = lastModified.trim()
            if (raw.isEmpty()) return 0L
            return runCatching {
                ZonedDateTime.parse(raw.substringAfter(", ", raw), HTTP_DATE).toInstant().toEpochMilli()
            }.recoverCatching {
                Instant.parse(raw).toEpochMilli()   // 少数服务器返回 ISO-8601
            }.getOrDefault(0L)
        }

        /**
         * 排序用的时间：优先取文件名里我们自己写入的时间戳（Await-backup-yyyyMMdd-HHmmss.json），
         * 它由本机生成、精确且一定存在；其次才用服务器的修改时间——有的服务器根本不返回
         * getlastmodified，那时若只按文件名排序，目录里的其他文件会盖在最新备份上面。
         */
        fun backupTimeMillis(): Long {
            val stamp = NAME_STAMP.find(name)?.groupValues?.get(1)
            if (stamp != null) {
                runCatching {
                    return LocalDateTime.parse(stamp, NAME_STAMP_FORMAT)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
            return modifiedAtMillis()
        }

        /** 「2026-08-22 18:00」；无法解析时原样返回服务器给的字符串 */
        fun modifiedText(): String {
            val millis = modifiedAtMillis()
            if (millis <= 0L) return lastModified
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            return "%04d-%02d-%02d %02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
        }
    }

    /** 连通性检查：目录不存在时顺手创建，返回给用户看的中文结论 */
    fun testConnection(config: WebDavConfig): Result<String> = runCatching {
        if (!config.isValid) error("请先填写服务器地址、账号与密码")
        when (val code = statusOf(config, config.dirUrl(), "PROPFIND", depth = "0")) {
            in 200..299 -> return@runCatching "连接正常，备份目录可用"
            401 -> error("账号或密码不正确")
            403 -> error("服务器拒绝访问（403），请检查账号权限")
            404, 409 -> Unit // 目录不存在，下面尝试创建
            else -> error("服务器返回 HTTP $code")
        }
        // 目录不存在：先确认根地址与凭证没问题，再建目录
        when (val rootCode = statusOf(config, config.rootUrl(), "PROPFIND", depth = "0")) {
            in 200..299 -> Unit
            401 -> error("账号或密码不正确")
            404 -> error("服务器地址不正确（根路径 404）")
            else -> error("服务器返回 HTTP $rootCode")
        }
        ensureDir(config).getOrThrow()
        "连接正常，已创建备份目录「${config.dir}」"
    }

    /** 确保备份目录存在；已存在（405 / 301）视为成功 */
    fun ensureDir(config: WebDavConfig): Result<Unit> = runCatching {
        when (val code = statusOf(config, config.dirUrl(), "MKCOL")) {
            in 200..299, 301, 405 -> Unit
            401 -> error("账号或密码不正确")
            409 -> error("上级目录不存在，请先在网盘中创建「${config.dir}」的上级目录")
            else -> error("创建备份目录失败（HTTP $code）")
        }
    }

    /** 上传（覆盖同名文件） */
    fun put(config: WebDavConfig, name: String, bytes: ByteArray): Result<Unit> = runCatching {
        val conn = open(config, config.fileUrl(name), "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setFixedLengthStreamingMode(bytes.size)
        try {
            conn.outputStream.use { it.write(bytes) }
            when (val code = conn.responseCode) {
                in 200..299 -> Unit
                401 -> error("账号或密码不正确")
                403 -> error("服务器拒绝写入（403）")
                404, 409 -> error("备份目录不存在，请先「测试连接」或在网盘中手动创建")
                507 -> error("网盘空间不足")
                else -> error("上传失败（HTTP $code）")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 下载文件内容 */
    fun get(config: WebDavConfig, name: String): Result<String> = runCatching {
        val conn = open(config, config.fileUrl(name), "GET")
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().readText()
                401 -> error("账号或密码不正确")
                404 -> error("云端没有找到「$name」")
                else -> error("下载失败（HTTP $code）")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun delete(config: WebDavConfig, name: String): Result<Unit> = runCatching {
        val code = statusOf(config, config.fileUrl(name), "DELETE")
        if (code !in 200..299 && code != 404) error("删除失败（HTTP $code）")
    }

    /**
     * 列出备份目录下的 .json 文件（按名称倒序，最新在前）。
     * 不带请求体的 PROPFIND 等价于 allprop，所有 WebDAV 服务器都支持。
     */
    fun list(config: WebDavConfig): Result<List<Entry>> = runCatching {
        val conn = open(config, config.dirUrl(), "PROPFIND")
        conn.setRequestProperty("Depth", "1")
        val body = try {
            when (val code = conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().readText()
                401 -> error("账号或密码不正确")
                404 -> error("备份目录不存在")
                else -> error("读取云端目录失败（HTTP $code）")
            }
        } finally {
            conn.disconnect()
        }
        parseListing(body)
            .filter { it.name.endsWith(".json", true) }
            .sortedWith(compareByDescending<Entry> { it.backupTimeMillis() }.thenByDescending { it.name })
    }

    /** 解析 multistatus XML：逐个 <response> 取出 href / getlastmodified / getcontentlength */
    internal fun parseListing(xml: String): List<Entry> {
        val blocks = Regex(
            "<(?:[A-Za-z0-9]+:)?response[\\s>].*?</(?:[A-Za-z0-9]+:)?response>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(xml).map { it.value }.toList()

        return blocks.mapNotNull { block ->
            val href = tag(block, "href") ?: return@mapNotNull null
            val path = href.substringBefore('?').trimEnd('/')
            val raw = path.substringAfterLast('/')
            if (raw.isBlank()) return@mapNotNull null
            val name = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            Entry(
                name = name,
                lastModified = tag(block, "getlastmodified").orEmpty(),
                size = tag(block, "getcontentlength")?.toLongOrNull() ?: 0L
            )
        }
    }

    private fun tag(block: String, name: String): String? =
        Regex(
            "<(?:[A-Za-z0-9]+:)?$name[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?$name>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(block)?.groupValues?.get(1)?.trim()

    /** 发一个无请求体的方法，只取状态码 */
    private fun statusOf(config: WebDavConfig, url: String, method: String, depth: String? = null): Int {
        val conn = open(config, url, method)
        depth?.let { conn.setRequestProperty("Depth", it) }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun open(config: WebDavConfig, url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Await-Android-Backup")
        conn.setRequestProperty("Connection", "close")
        conn.setRequestProperty("Authorization", config.authHeader())
        conn.setMethodCompat(method)
        return conn
    }

    /**
     * Android 的 HttpURLConnection（OkHttp 实现）允许 PROPFIND/MKCOL；
     * 万一某些 ROM 换成了严格的 JDK 实现，就反射改写 method 字段兜底。
     */
    private fun HttpURLConnection.setMethodCompat(method: String) {
        if (runCatching { requestMethod = method }.isSuccess) return
        val targets = mutableListOf<Any>(this)
        runCatching {
            javaClass.getDeclaredField("delegate")
                .apply { isAccessible = true }
                .get(this)
                ?.let { targets += it }
        }
        for (target in targets) {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                val cls = c
                val ok = runCatching {
                    cls.getDeclaredField("method").apply { isAccessible = true }.set(target, method)
                }.isSuccess
                if (ok) return
                c = cls.superclass
            }
        }
        error("当前系统不支持 WebDAV 的 $method 方法")
    }

    /** 路径分段编码：空格转 %20 而非 +，斜杠保留为分隔符 */
    internal fun encodePath(path: String): String = path.split('/').joinToString("/") { seg ->
        URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
    }
}
