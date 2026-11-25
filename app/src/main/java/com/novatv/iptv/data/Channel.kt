package com.novatv.iptv.data

data class Channel(
    val name: String,
    val url: String,
    val group: String = "Sin categoría",
    val logo: String? = null,
    val kind: String = "live",
    val sourceId: String? = null,
    val description: String? = null,
)

object M3uParser {
    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var title: String? = null
        var group = "Sin categoría"
        var logo: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = Regex("([\\w-]+)=\\\"([^\\\"]*)\\\"").findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    title = line.substringAfterLast(',').trim().ifBlank { "Canal sin nombre" }
                    group = attrs["group-title"]?.takeIf(String::isNotBlank) ?: "Sin categoría"
                    logo = attrs["tvg-logo"]?.takeIf(String::isNotBlank)
                }
                line.isNotEmpty() && !line.startsWith("#") && title != null -> {
                    result += Channel(title!!, line, group, logo)
                    title = null; group = "Sin categoría"; logo = null
                }
            }
        }
        return result
    }
}
