package com.ultratv.tv.nativeapp.data.xmltv

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class XmltvParserTest {

    private val parser = XmltvParser(OkHttpClient())

    @Test
    fun unmappedProgrammeDoesNotConsumeFollowingMappedProgramme() {
        val input = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme channel="missing" start="20260909000000 +0000" stop="20260909003000 +0000">
                <title>Ignore me</title>
                <desc>Unmapped channel</desc>
              </programme>
              <programme channel="mapped" start="20260909003000 +0000" stop="20260909010000 +0000">
                <title>Keep me</title>
                <desc>Mapped channel</desc>
              </programme>
            </tv>
        """.trimIndent()

        val rows = parser.parse(
            ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
            mapOf("mapped" to 42L),
        )

        assertEquals(1, rows.size)
        assertEquals(42L, rows.single().channelId)
        assertEquals("Keep me", rows.single().title)
    }

    @Test
    fun nestedUnknownElementDoesNotBreakProgrammeParsing() {
        val input = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme channel="mapped" start="20260909010000 +0000" stop="20260909013000 +0000">
                <credits>
                  <director>Someone</director>
                </credits>
                <title>Still parsed</title>
                <desc>Nested metadata should be skipped safely</desc>
              </programme>
            </tv>
        """.trimIndent()

        val rows = parser.parse(
            ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
            mapOf("mapped" to 7L),
        )

        assertEquals(1, rows.size)
        assertEquals("Still parsed", rows.single().title)
    }
}
