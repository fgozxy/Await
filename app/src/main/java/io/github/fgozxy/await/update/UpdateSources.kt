package io.github.fgozxy.await.update

import kotlinx.coroutines.CancellationException

/** 先通过自托管 HubProxy 加速，失败后尝试 GitHub 原地址。 */
object UpdateSources {
    const val ACCELERATOR = "https://hubproxy.zlibza.com"

    private fun supportedOriginal(url: String): Boolean = url == ReleaseInfo.LATEST_API ||
        Regex("https://github\\.com/fgozxy/Await/releases/download/v([0-9]+\\.[0-9]+\\.[0-9]+)/(Await-v\\1\\.apk|SHA256SUMS\\.txt)").matches(url)

    fun urls(original: String): List<String> {
        require(supportedOriginal(original)) { "更新地址无效" }
        return listOf("$ACCELERATOR/${original.removePrefix("https://")}", original)
    }

    fun trustedProxyUrl(url: String): Boolean = url.startsWith("$ACCELERATOR/") &&
        supportedOriginal("https://" + url.removePrefix("$ACCELERATOR/"))

    suspend fun <T> withFallback(original: String, attempt: suspend (String) -> T): T {
        var failure: Exception? = null
        for (url in urls(original)) {
            try { return attempt(url) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        throw failure ?: UpdateException("暂时无法获取更新，请稍后重试")
    }
}
