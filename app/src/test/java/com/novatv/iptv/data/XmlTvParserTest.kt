package com.novatv.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class XmlTvParserTest {
    @Test fun mapsProgrammeToXmlTvChannel() {
        val xml = """<?xml version="1.0"?><tv>
          <channel id="news"><display-name>News</display-name></channel>
          <programme start="20261125180000 +0000" stop="20261125190000 +0000" channel="news">
            <title>Evening bulletin</title><desc>Daily headlines</desc>
          </programme>
        </tv>"""
        val guide = XmlTvParser.parse(xml)
        assertEquals("Evening bulletin", guide.getValue("news").single().title)
        assertEquals("Daily headlines", guide.getValue("news").single().description)
    }
}
