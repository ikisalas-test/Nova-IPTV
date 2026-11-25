package com.novatv.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserTest {
    @Test fun parsesChannelsAndGroups() {
        val channels = M3uParser.parse("""#EXTM3U
#EXTINF:-1 tvg-id="news" tvg-logo="https://logo.test/a.png" group-title="Noticias",Canal Uno
https://stream.test/uno.m3u8
#EXTINF:-1,Canal Dos
https://stream.test/dos.m3u8
""")
        assertEquals(2, channels.size)
        assertEquals("Canal Uno", channels[0].name)
        assertEquals("Noticias", channels[0].group)
        assertEquals("https://logo.test/a.png", channels[0].logo)
        assertEquals("news", channels[0].epgId)
        assertEquals("Sin categoría", channels[1].group)
    }

    @Test fun keepsCommasInChannelNamesAndSkipsOrphanUrls() {
        val channels = M3uParser.parse("""#EXTM3U
https://stream.test/orphan.m3u8
#EXTINF:-1 group-title="World",News, International
#EXTVLCOPT:http-user-agent=Nova
https://stream.test/news.m3u8
""")
        assertEquals(1, channels.size)
        assertEquals("News, International", channels.single().name)
    }
}
