package org.tasks.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CalendarDaysTest {
    private val zone = ZoneId.of("America/Chicago")

    private fun millis(date: LocalDate, hour: Int = 12) =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun weekStartsOnSundayWhenConfigured() {
        val week = CalendarDays.weekDays(LocalDate.of(2026, 11, 7), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), week.first())
        assertEquals(LocalDate.of(2026, 11, 7), week.last())
        assertEquals(7, week.size)
    }

    @Test
    fun weekStartsOnMonday() {
        val week = CalendarDays.weekDays(LocalDate.of(2026, 11, 7), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 2), week.first())
        assertEquals(LocalDate.of(2026, 11, 8), week.last())
    }

    @Test
    fun weekSpansMonthBoundary() {
        val week = CalendarDays.weekDays(LocalDate.of(2026, 12, 31), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 12, 28), week.first())
        assertEquals(LocalDate.of(2027, 1, 3), week.last())
    }

    @Test
    fun weekAcrossDstChangeHasSevenDistinctDays() {
        // US DST ends Sunday 2026-11-01
        val week = CalendarDays.weekDays(LocalDate.of(2026, 11, 1), DayOfWeek.SUNDAY)
        assertEquals(7, week.toSet().size)
        assertEquals(LocalDate.of(2026, 11, 7), week.last())
    }

    @Test
    fun weekdayHeaderRotates() {
        assertEquals(DayOfWeek.WEDNESDAY, CalendarDays.weekdays(DayOfWeek.WEDNESDAY).first())
        assertEquals(DayOfWeek.TUESDAY, CalendarDays.weekdays(DayOfWeek.WEDNESDAY).last())
    }

    @Test
    fun dotsIgnoreTasksWithoutDueDate() {
        val days = CalendarDays.daysWithTasks(
            listOf(0L, millis(LocalDate.of(2026, 11, 7), 23), millis(LocalDate.of(2026, 11, 7), 1)),
            zone,
        )
        assertEquals(setOf(LocalDate.of(2026, 11, 7)), days)
    }

    @Test
    fun lateEveningStaysOnSameLocalDay() {
        val due = LocalDate.of(2026, 11, 7).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 7), CalendarDays.date(due, zone))
    }

    @Test
    fun overdueOnlyShownUnderToday() {
        val today = LocalDate.of(2026, 11, 7)
        val yesterday = millis(today.minusDays(1))
        assertTrue(CalendarDays.isShownOn(yesterday, false, today, today, zone))
        assertFalse(CalendarDays.isShownOn(yesterday, true, today, today, zone))
        assertFalse(CalendarDays.isShownOn(yesterday, false, today.plusDays(1), today, zone))
    }

    @Test
    fun taskShownOnItsOwnDay() {
        val today = LocalDate.of(2026, 11, 7)
        val tomorrow = today.plusDays(1)
        assertTrue(CalendarDays.isShownOn(millis(tomorrow), false, tomorrow, today, zone))
        assertFalse(CalendarDays.isShownOn(millis(tomorrow), false, today, today, zone))
        assertFalse(CalendarDays.isShownOn(0L, false, today, today, zone))
    }

    @Test
    fun weekWindowKeepsPagesFixedWhilePaging() {
        val today = LocalDate.of(2026, 11, 8)
        val thisWeek = CalendarDays.weekWindow(today, today, DayOfWeek.SUNDAY)
        val nextWeek = CalendarDays.weekWindow(today, today.plusDays(7), DayOfWeek.SUNDAY)
        assertEquals(thisWeek.starts, nextWeek.starts)
        assertEquals(thisWeek.selectedIndex + 1, nextWeek.selectedIndex)
        assertEquals(today, thisWeek.starts[thisWeek.selectedIndex])
    }

    @Test
    fun weekWindowRecentresPastItsEdge() {
        val today = LocalDate.of(2026, 11, 8)
        val far = today.plusWeeks(30)
        val window = CalendarDays.weekWindow(today, far, DayOfWeek.SUNDAY)
        assertEquals(far, window.starts[window.selectedIndex])
        assertEquals(window.starts.size / 2, window.selectedIndex)
    }

    @Test
    fun weekWindowPagesStartOnFirstDayOfWeek() {
        val today = LocalDate.of(2026, 11, 11)
        val window = CalendarDays.weekWindow(today, today, DayOfWeek.MONDAY)
        assertTrue(window.starts.all { it.dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test
    fun monthGridRowsFollowTheMonth() {
        // February 2026 starts on a Sunday and has 28 days: exactly 4 rows
        assertEquals(4, CalendarDays.monthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).size)
        // ... but with Monday weeks it spills into a fifth
        assertEquals(5, CalendarDays.monthGrid(YearMonth.of(2026, 2), DayOfWeek.MONDAY).size)
        // November 2026 starts on a Sunday and has 30 days
        assertEquals(5, CalendarDays.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY).size)
        // August 2026 starts on a Saturday and has 31 days
        assertEquals(6, CalendarDays.monthGrid(YearMonth.of(2026, 8), DayOfWeek.SUNDAY).size)
    }

    @Test
    fun monthGridCoversWholeMonthWithFullWeeks() {
        val month = YearMonth.of(2026, 8)
        val grid = CalendarDays.monthGrid(month, DayOfWeek.MONDAY)
        assertTrue(grid.all { it.size == 7 && it.first().dayOfWeek == DayOfWeek.MONDAY })
        val days = grid.flatten()
        assertEquals(days.distinct(), days)
        assertTrue(days.containsAll((1..31).map { month.atDay(it) }))
    }

    @Test
    fun pagingMonthsSelectsTheFirstOrToday() {
        val today = LocalDate.of(2026, 11, 8)
        assertEquals(LocalDate.of(2026, 12, 1), CalendarDays.pageMonth(today, today, 1))
        assertEquals(LocalDate.of(2026, 10, 1), CalendarDays.pageMonth(today, today, -1))
        assertEquals(today, CalendarDays.pageMonth(LocalDate.of(2026, 12, 1), today, -1))
        assertEquals(LocalDate.of(2027, 1, 1), CalendarDays.pageMonth(LocalDate.of(2026, 12, 20), today, 1))
    }

    @Test
    fun monthWindowKeepsPagesFixedWhilePaging() {
        val today = LocalDate.of(2026, 11, 8)
        val now = CalendarDays.monthWindow(today, today)
        val next = CalendarDays.monthWindow(today, LocalDate.of(2026, 12, 1))
        assertEquals(now.months, next.months)
        assertEquals(now.selectedIndex + 1, next.selectedIndex)
        assertEquals(YearMonth.of(2026, 11), now.months[now.selectedIndex])
    }

    @Test
    fun monthWindowRecentresPastItsEdge() {
        val today = LocalDate.of(2026, 11, 8)
        val far = LocalDate.of(2027, 11, 8)
        val window = CalendarDays.monthWindow(today, far)
        assertEquals(YearMonth.of(2027, 11), window.months[window.selectedIndex])
        assertEquals(window.months.size / 2, window.selectedIndex)
    }

    @Test
    fun monthModeNeedsTallerWidgets() {
        assertFalse(CalendarDays.isMonthMode(230))
        assertTrue(CalendarDays.isMonthMode(358))
    }
}
