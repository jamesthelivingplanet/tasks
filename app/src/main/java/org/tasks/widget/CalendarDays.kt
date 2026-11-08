package org.tasks.widget

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/** Pure date math for the calendar widget. */
internal object CalendarDays {
    fun date(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** The seven days of the week containing [day], starting on [firstDay]. */
    fun weekDays(day: LocalDate, firstDay: DayOfWeek): List<LocalDate> {
        val start = day.with(TemporalAdjusters.previousOrSame(firstDay))
        return (0L until 7L).map { start.plusDays(it) }
    }

    /**
     * Pages for the week strip: the start of each week in a window around [today], and the page
     * showing [selected]. Pages are numbered from a fixed origin so that paging changes the
     * displayed page index. Past the window the strip recentres on [selected] (no animation).
     */
    fun weekWindow(
        today: LocalDate,
        selected: LocalDate,
        firstDay: DayOfWeek,
        weeksEachSide: Int = WEEKS_EACH_SIDE,
    ): WeekWindow {
        val todayStart = weekDays(today, firstDay).first()
        val selectedStart = weekDays(selected, firstDay).first()
        val weeksFromToday = ChronoUnit.WEEKS.between(todayStart, selectedStart)
        val centre = if (abs(weeksFromToday) < weeksEachSide) todayStart else selectedStart
        val first = centre.minusWeeks(weeksEachSide.toLong())
        return WeekWindow(
            starts = (0L..2L * weeksEachSide).map { first.plusWeeks(it) },
            selectedIndex = ChronoUnit.WEEKS.between(first, selectedStart).toInt(),
        )
    }

    class WeekWindow(val starts: List<LocalDate>, val selectedIndex: Int)

    /** Month pages for the month grid, numbered from a fixed origin like [weekWindow]. */
    fun monthWindow(
        today: LocalDate,
        selected: LocalDate,
        monthsEachSide: Int = MONTHS_EACH_SIDE,
    ): MonthWindow {
        val todayMonth = YearMonth.from(today)
        val selectedMonth = YearMonth.from(selected)
        val monthsFromToday = ChronoUnit.MONTHS.between(todayMonth, selectedMonth)
        val centre = if (abs(monthsFromToday) < monthsEachSide) todayMonth else selectedMonth
        val first = centre.minusMonths(monthsEachSide.toLong())
        return MonthWindow(
            months = (0L..2L * monthsEachSide).map { first.plusMonths(it) },
            selectedIndex = ChronoUnit.MONTHS.between(first, selectedMonth).toInt(),
        )
    }

    class MonthWindow(val months: List<YearMonth>, val selectedIndex: Int)

    /** The weeks shown for [month]: only as many rows as the month needs (4 to 6). */
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<List<LocalDate>> {
        val start = weekDays(month.atDay(1), firstDay).first()
        val rows = ChronoUnit.WEEKS.between(start, weekDays(month.atEndOfMonth(), firstDay).first()) + 1
        return (0L until rows).map { weekDays(start.plusWeeks(it), firstDay) }
    }

    /**
     * The day selected after paging the month grid by [delta] months: the 1st of that month, or
     * [today] when paging onto the current month.
     */
    fun pageMonth(selected: LocalDate, today: LocalDate, delta: Int): LocalDate {
        val month = YearMonth.from(selected).plusMonths(delta.toLong())
        return if (month == YearMonth.from(today)) today else month.atDay(1)
    }

    /**
     * The dates the pages around [selected] can show, as a start (inclusive) and end (exclusive).
     * This covers every cell of [weekWindow] or [monthWindow], including the adjacent-month days
     * a month grid dims.
     */
    fun visibleDates(
        today: LocalDate,
        selected: LocalDate,
        firstDay: DayOfWeek,
        monthMode: Boolean,
    ): Pair<LocalDate, LocalDate> =
        if (monthMode) {
            val months = monthWindow(today, selected).months
            months.first().atDay(1).minusDays(7) to months.last().atEndOfMonth().plusDays(8)
        } else {
            val starts = weekWindow(today, selected, firstDay).starts
            starts.first() to starts.last().plusWeeks(1)
        }

    /**
     * SQL condition on `tasks` for the rows the calendar can use: tasks due from [start] up to
     * (not including) [end], plus uncompleted overdue tasks when [includeOverdue] (the list for
     * today shows them).
     */
    fun dueCondition(
        start: LocalDate,
        end: LocalDate,
        includeOverdue: Boolean,
        zone: ZoneId,
    ): String {
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = end.atStartOfDay(zone).toInstant().toEpochMilli()
        val overdue = if (includeOverdue) {
            " OR (tasks.dueDate > 0 AND tasks.dueDate < $from AND tasks.completed = 0)"
        } else {
            ""
        }
        return "((tasks.dueDate >= $from AND tasks.dueDate < $to)$overdue)"
    }

    /** The month grid needs more room than a 4x2 widget has. */
    fun isMonthMode(heightDp: Int): Boolean = heightDp >= MONTH_MIN_HEIGHT_DP

    private const val WEEKS_EACH_SIDE = 8
    private const val MONTHS_EACH_SIDE = 3
    private const val MONTH_MIN_HEIGHT_DP = 300

    /** Weekday header order for a week starting on [firstDay]. */
    fun weekdays(firstDay: DayOfWeek): List<DayOfWeek> =
        (0L until 7L).map { firstDay.plus(it) }

    /** Days that have at least one due date, ignoring tasks without one (`dueDate == 0`). */
    fun daysWithTasks(dueDates: Iterable<Long>, zone: ZoneId): Set<LocalDate> =
        dueDates.filter { it > 0 }.mapTo(HashSet()) { date(it, zone) }

    /**
     * Whether a task belongs in the list for [selected]: it is due that day, or it is an
     * uncompleted overdue task and [selected] is today.
     */
    fun isShownOn(
        dueDate: Long,
        completed: Boolean,
        selected: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): Boolean {
        if (dueDate <= 0) return false
        val due = date(dueDate, zone)
        return due == selected || (selected == today && due.isBefore(today) && !completed)
    }
}
