package io.github.fgozxy.await.sync

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LunarCompatibilityTest {
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun oldServerStillAcceptsSolarEventsAndEmptyPayload() {
        ServerClient.checkCalendar(json("{}"), json("""{"events":[]}"""))
        ServerClient.checkCalendar(json("{}"), json("""{"events":[{"date":"2026-10-01"}]}"""))
    }

    @Test fun lunarRequiresExplicitServerSupportBeforeUploading() {
        val lunar = json("""{"events":[{"calendarType":"LUNAR"}]}""")
        for (status in listOf("{}", """{"lunarCalendarProtocol":0}""")) {
            try {
                ServerClient.checkCalendar(json(status), lunar)
                fail("旧服务器必须拒绝农历数据")
            } catch (e: ServerException) {
                assertFalse(e.retryable)
                assertTrue(e.message.orEmpty().contains("升级"))
            }
        }
        ServerClient.checkCalendar(json("""{"lunarCalendarProtocol":1}"""), lunar)
    }
}
