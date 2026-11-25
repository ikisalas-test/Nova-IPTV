package com.novatv.iptv.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource

/** Parses XMLTV schedules without resolving external entities or DTDs. */
object XmlTvParser {
    fun parse(xml: String): Map<String, List<EpgProgram>> {
        val schedules = linkedMapOf<String, MutableList<Pair<Long, EpgProgram>>>()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val reader = factory.newSAXParser().xmlReader.apply {
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        reader.contentHandler = object : DefaultHandler() {
            var channel = ""; var start = ""; var end = ""; var title = ""; var desc = ""
            var field = ""; var text = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                when (qName.lowercase(Locale.ROOT)) {
                    "programme" -> { channel = attrs.getValue("channel") ?: ""; start = attrs.getValue("start") ?: ""; end = attrs.getValue("stop") ?: ""; title = ""; desc = "" }
                    "title", "desc" -> { field = qName.lowercase(Locale.ROOT); text = StringBuilder() }
                }
            }
            override fun characters(ch: CharArray, offset: Int, length: Int) { if (field.isNotEmpty()) text.append(ch, offset, length) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName.lowercase(Locale.ROOT)) {
                    "title" -> { title = text.toString().trim(); field = "" }
                    "desc" -> { desc = text.toString().trim(); field = "" }
                    "programme" -> {
                        val epoch = timestamp(start)
                        if (channel.isNotBlank() && epoch != null && title.isNotBlank()) {
                            schedules.getOrPut(channel) { mutableListOf() }.add(epoch to EpgProgram(title, start, end, desc))
                        }
                    }
                }
            }
        }
        reader.parse(InputSource(StringReader(xml)))
        return schedules.mapValues { (_, shows) -> shows.sortedBy { it.first }.map { it.second } }
    }

    private fun timestamp(value: String): Long? {
        val formats = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss")
        for (format in formats) runCatching {
            val parser = SimpleDateFormat(format, Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
            return parser.parse(value.trim())?.time
        }
        return null
    }
}
