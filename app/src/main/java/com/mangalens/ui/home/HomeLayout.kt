package com.mangalens.ui.home

import org.json.JSONArray
import org.json.JSONObject

enum class HomeModule(val id: String, val label: String, val defaultVisible: Boolean) {
    QUICK_ACTIONS("quick_actions", "Quick actions", true),
    CONTINUE_READING("continue_reading", "Continue Reading", true),
    RECENT_MANGA("recent_manga", "Recent Manga", true),
    SOURCE_CHAPTERS("source_chapters", "Source chapters", true),
    OREZ_AI("orez_ai", "Orez AI", true),
    CONTINUE_WATCHING("continue_watching", "Continue Watching", true),
    DOWNLOADS("downloads", "Downloads", false),
    TRANSLATION_QUEUE("translation_queue", "Translation Queue", false),
    BOOKMARKS("bookmarks", "Bookmarks", false)
}

data class HomeLayout(
    val order: List<HomeModule> = HomeModule.entries.toList(),
    val hidden: Set<HomeModule> = HomeModule.entries.filterNot { it.defaultVisible }.toSet()
) {
    val visibleModules: List<HomeModule> get() = order.filterNot { it in hidden }
}

/** One bounded preference record; unknown future module IDs never become fake sections. */
object HomeLayoutPolicy {
    private const val MAX_ENCODED_CHARS = 4096

    fun normalize(value: HomeLayout): HomeLayout = HomeLayout(
        (value.order.distinct() + HomeModule.entries.filterNot { it in value.order }).toList(), value.hidden.toSet())

    fun decode(value: String?): HomeLayout {
        if (value == null || value.length > MAX_ENCODED_CHARS) return HomeLayout()
        return runCatching {
            val json = JSONObject(value)
            require(json.getInt("version") == 1)
            val order = readModules(json.getJSONArray("order"))
            val hidden = readModules(json.getJSONArray("hidden")).toSet()
            val addedDefaults = HomeModule.entries.filter { it !in order && !it.defaultVisible }
            normalize(HomeLayout(order, hidden + addedDefaults))
        }.getOrDefault(HomeLayout())
    }

    fun encode(value: HomeLayout): String {
        val layout = normalize(value)
        return JSONObject().put("version", 1).put("order", JSONArray(layout.order.map { it.id }))
            .put("hidden", JSONArray(layout.order.filter { it in layout.hidden }.map { it.id })).toString()
            .also { require(it.length <= MAX_ENCODED_CHARS) }
    }

    fun move(value: HomeLayout, module: HomeModule, direction: Int): HomeLayout {
        val layout = normalize(value)
        if (direction != -1 && direction != 1) return layout
        val current = layout.order.indexOf(module)
        val target = current + direction
        if (current < 0 || target !in layout.order.indices) return layout
        val order = layout.order.toMutableList()
        order[current] = order[target]
        order[target] = module
        return layout.copy(order = order.toList())
    }

    fun setVisible(value: HomeLayout, module: HomeModule, visible: Boolean): HomeLayout =
        normalize(value).let { it.copy(hidden = if (visible) it.hidden - module else it.hidden + module) }

    fun reset(): HomeLayout = HomeLayout()

    private fun readModules(array: JSONArray): List<HomeModule> {
        require(array.length() <= 64)
        return (0 until array.length()).mapNotNull { index ->
            val id = array.get(index) as? String ?: error("Invalid Home module ID.")
            require(id.length <= 64)
            HomeModule.entries.firstOrNull { it.id == id }
        }.distinct()
    }
}

interface HomeLayoutStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

class HomeLayoutStore(private val storage: HomeLayoutStorage) {
    fun load(): HomeLayout = runCatching { HomeLayoutPolicy.decode(storage.read()) }.getOrDefault(HomeLayout())
    fun save(value: HomeLayout): Boolean = runCatching { storage.write(HomeLayoutPolicy.encode(value)) }.getOrDefault(false)
}
