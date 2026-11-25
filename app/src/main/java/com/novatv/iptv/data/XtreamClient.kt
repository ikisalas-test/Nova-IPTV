package com.novatv.iptv.data

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class XtreamLabels(
    val uncategorized: String,
    val untitled: String,
    val defaultEpisode: (Int) -> String,
    val season: (String) -> String,
    val seasonEpisode: (String, String) -> String,
)

/** Small synchronous client; call it from Dispatchers.IO. */
class XtreamClient(
    server: String,
    private val username: String,
    private val password: String,
    private val labels: XtreamLabels = XtreamLabels("Sin categoría", "Sin título", { "Episodio $it" }, { "Temporada $it" }, { season, title -> "T$season · $title" }),
) {
    private val base = server.trim().trimEnd('/')

    fun list(kind: String): List<Channel> {
        val action = when (kind) { "movies" -> "get_vod_streams"; "series" -> "get_series"; else -> "get_live_streams" }
        val categoryAction = when (kind) { "movies" -> "get_vod_categories"; "series" -> "get_series_categories"; else -> "get_live_categories" }
        val categories = (request(categoryAction) as? JSONArray)?.let { rows ->
            buildMap {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optString("category_id").takeIf { it != "null" }.orEmpty()
                    val name = row.optString("category_name", row.optString("name"))
                    if (id.isNotBlank() && name.isNotBlank()) put(id, name)
                }
            }
        }.orEmpty()
        val rows = request(action) as? JSONArray ?: return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString(if (kind == "series") "series_id" else if (kind == "movies") "stream_id" else "stream_id")
                if (id.isBlank() || id == "null") continue
                val title = row.optString("name").ifBlank { labels.untitled }
                val categoryId = row.optString("category_id").takeIf { it != "null" }.orEmpty()
                val group = row.optString("category_name").ifBlank { categories[categoryId] ?: labels.uncategorized }
                val ext = row.optString("container_extension").ifBlank { "mp4" }
                val path = when (kind) {
                    "movies" -> "movie/$username/$password/$id.$ext"
                    "series" -> ""
                    else -> "live/$username/$password/$id.m3u8"
                }
                add(Channel(title, if (path.isBlank()) "" else "$base/$path", group, row.optString("stream_icon").takeIf { it.startsWith("http") }, kind, id))
            }
        }
    }

    fun episodes(seriesId: String): List<Channel> {
        val response = request("get_series_info", "series_id" to seriesId) as? JSONObject ?: return emptyList()
        val episodes = response.optJSONObject("episodes") ?: return emptyList()
        return buildList {
            val seasons = episodes.keys()
            while (seasons.hasNext()) {
                val season = seasons.next()
                val list = episodes.optJSONArray(season) ?: continue
                for (i in 0 until list.length()) {
                    val row = list.optJSONObject(i) ?: continue
                    val id = row.optString("id")
                    if (id.isBlank()) continue
                    val ext = row.optString("container_extension").ifBlank { "mp4" }
                    val title = row.optString("title").ifBlank { labels.defaultEpisode(i + 1) }
                    add(Channel(labels.seasonEpisode(season, title), "$base/series/$username/$password/$id.$ext", labels.season(season), kind = "episode", sourceId = id))
                }
            }
        }
    }

    fun epg(streamId: String, limit: Int = 12): List<EpgProgram> {
        val response = request("get_short_epg", "stream_id" to streamId, "limit" to limit.toString()) as? JSONObject ?: return emptyList()
        val rows = response.optJSONArray("epg_listings") ?: return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                fun decoded(key: String): String = runCatching {
                    String(Base64.decode(row.optString(key), Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault(row.optString(key))
                add(EpgProgram(decoded("title"), row.optString("start"), row.optString("end"), decoded("description")))
            }
        }
    }

    private fun request(action: String, vararg extras: Pair<String, String>): Any? {
        val params = listOf("username" to username, "password" to password, "action" to action) + extras
        val query = params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
        val connection = URL("$base/player_api.php?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 12000; connection.readTimeout = 20000
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return when (text.trimStart().firstOrNull()) { '[' -> JSONArray(text); '{' -> JSONObject(text); else -> null }
        } finally { connection.disconnect() }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
