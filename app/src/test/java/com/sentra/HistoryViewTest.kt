package com.sentra

import com.sentra.care.HistoryMode
import com.sentra.care.ago
import com.sentra.care.dayName
import com.sentra.care.historyPeriod
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The Riwayat periods, matching the web page's range(). */
class HistoryViewTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    // Monday 9 Nov 2026
    private val now = at(2026, 11, 9, 15)

    @Test fun todayRunsMidnightToMidnight() {
        val p = historyPeriod(HistoryMode.DAY, 0, now, zone)
        assertEquals(at(2026, 11, 9), p.from)
        assertEquals(at(2026, 11, 10), p.until)
        assertEquals("Hari ini", p.title)
    }

    @Test fun yesterdayIsOneStepBack() {
        val p = historyPeriod(HistoryMode.DAY, -1, now, zone)
        assertEquals(at(2026, 11, 8), p.from)
        assertEquals("Kemarin", p.title)
    }

    @Test fun weekStartsOnMondayEvenOnASunday() {
        val sunday = at(2026, 11, 15, 23)
        val p = historyPeriod(HistoryMode.WEEK, 0, sunday, zone)
        assertEquals(at(2026, 11, 9), p.from)
        assertEquals(at(2026, 11, 16), p.until)
        assertEquals("Minggu ini", p.title)
    }

    @Test fun earlierWeeksStepBackAWeekAtATime() {
        assertEquals("Minggu lalu", historyPeriod(HistoryMode.WEEK, -1, now, zone).title)
        assertEquals(at(2026, 10, 26), historyPeriod(HistoryMode.WEEK, -2, now, zone).from)
    }

    @Test fun allHasNoLimits() {
        val p = historyPeriod(HistoryMode.ALL, 0, now, zone)
        assertEquals(0L, p.from)
        assertEquals(Long.MAX_VALUE, p.until)
    }

    @Test fun olderDaysAreNamed() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals(true, dayName(today.minusDays(3), today).contains("6"))
    }

    @Test fun agoMatchesTheWebWording() {
        assertEquals("baru saja", ago(now - 5_000, now))
        assertEquals("30 dtk lalu", ago(now - 30_000, now))
        assertEquals("5 mnt lalu", ago(now - 5 * 60_000, now))
        assertEquals("3 jam lalu", ago(now - 3 * 3_600_000, now))
        assertEquals("2 hari lalu", ago(now - 2 * 86_400_000, now))
    }
}
