package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 用户显式建过的分组名。
 *
 * 分组原本完全从日程里推导——有日程写了「生日」，就有「生日」这个分组。这样的分组
 * 在最后一条日程被移走时会凭空消失，也没法「先建好分组、再往里放日程」。
 * 所以显式建过的名字单独存一份，与推导出来的合并后才是完整的分组列表。
 *
 * 只存名字，不存归属关系：日程属于哪个分组仍由 [Event.groupName] 说了算，
 * 两处记录同一件事迟早会对不上。
 */
object GroupStore {

    private const val PREFS = "await_groups"
    private const val KEY = "names"

    private val gson = Gson()

    fun load(context: Context): List<String> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        val list = runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)
        }.getOrNull() ?: return emptyList()
        return list.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun save(context: Context, names: List<String>) {
        prefs(context).edit()
            .putString(KEY, gson.toJson(names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
            .apply()
    }

    /** 新建分组；名字为空或已存在返回 false */
    fun add(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val current = load(context)
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return false
        save(context, current + trimmed)
        return true
    }

    /** 删除分组名；日程上的归属由调用方另行处理 */
    fun remove(context: Context, names: Set<String>) {
        if (names.isEmpty()) return
        save(context, load(context).filterNot { it in names })
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
