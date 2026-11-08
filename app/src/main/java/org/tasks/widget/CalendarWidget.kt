package org.tasks.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.core.graphics.ColorUtils
import android.view.View
import android.widget.RemoteViews
import androidx.core.widget.RemoteViewsCompat
import androidx.core.widget.RemoteViewsCompat.RemoteCollectionItems
import com.todoroo.andlib.utility.AndroidUtilities.atLeastS
import com.todoroo.astrid.activity.MainActivity.Companion.FINISH_AFFINITY
import com.todoroo.astrid.subtasks.SubtasksHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.isoDayNumber
import org.tasks.R
import org.tasks.kmp.firstDayOfWeek
import org.tasks.widget.CalendarActionReceiver.Companion.ACTION_NEXT
import org.tasks.widget.CalendarActionReceiver.Companion.ACTION_PREVIOUS
import org.tasks.widget.CalendarActionReceiver.Companion.ACTION_SELECT_DAY
import org.tasks.widget.CalendarActionReceiver.Companion.ACTION_TODAY
import org.tasks.widget.CalendarActionReceiver.Companion.EXTRA_DAY
import org.tasks.data.dao.TaskDao
import org.tasks.extensions.estimateParcelSize
import org.tasks.extensions.setBackgroundColor
import org.tasks.extensions.setColorFilter
import org.tasks.filters.Filter
import org.tasks.intents.TaskIntents
import org.tasks.markdown.MarkdownProvider
import org.tasks.preferences.DefaultFilterProvider
import org.tasks.preferences.Preferences
import org.tasks.tasklist.HeaderFormatter
import org.tasks.themes.ThemeColor
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/**
 * A calendar widget: a week strip (4x2) or month grid (4x3 and up) above the task list for the
 * selected day. The only per-widget calendar state is the selected day
 * ([WidgetPreferences.calendarSelectedDay]); the strip/grid choice follows the widget's height.
 */
@AndroidEntryPoint
class CalendarWidget : AppWidgetProvider() {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var defaultFilterProvider: DefaultFilterProvider
    @Inject lateinit var subtasksHelper: SubtasksHelper
    @Inject lateinit var taskDao: TaskDao
    @Inject lateinit var chipProvider: WidgetChipProvider
    @Inject lateinit var markdownProvider: MarkdownProvider
    @Inject lateinit var headerFormatter: HeaderFormatter

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Timber.d("onUpdate appWidgetIds=${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { id ->
            try {
                val monthMode = isMonthMode(appWidgetManager.getAppWidgetOptions(id))
                val remoteViews = createWidget(context, id, monthMode)
                Timber.d("Widget $id main layout: ${Formatter.formatShortFileSize(context, remoteViews.estimateParcelSize().toLong())}")
                appWidgetManager.updateAppWidget(id, remoteViews)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        Timber.d(
            "onAppWidgetOptionsChanged appWidgetId=$appWidgetId " +
                    "maxHeight=${newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}dp"
        )
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPreferences(context, preferences, it).delete() }
    }

    private fun createWidget(context: Context, id: Int, monthMode: Boolean): RemoteViews {
        val widgetPreferences = WidgetPreferences(context, preferences, id)
        val header = widgetPreferences.getWidgetHeaderSettings()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val selected = widgetPreferences.calendarSelectedDay?.let(LocalDate::ofEpochDay) ?: today
        val filter = runBlocking { defaultFilterProvider.getFilterFromPreference(widgetPreferences.filterId) }
        val accent = ThemeColor(context, header.color)
        val onSurface = context.getColor(if (header.isDark) R.color.white_87 else R.color.black_87)
        val onSurfaceVariant = context.getColor(if (header.isDark) R.color.white_60 else R.color.black_60)
        val divider = context.getColor(if (header.isDark) R.color.white_12 else R.color.black_12)
        val onSurfaceDim = ColorUtils.setAlphaComponent(onSurface, DIMMED_ALPHA)
        val firstDay = DayOfWeek.of(firstDayOfWeek().isoDayNumber)

        val builder = TasksWidgetBuilder(
            subtasksHelper = subtasksHelper,
            widgetPreferences = widgetPreferences,
            filter = filter,
            context = context,
            widgetId = id,
            taskDao = taskDao,
            chipProvider = chipProvider,
            markdown = markdownProvider.markdown(false),
            headerFormatter = headerFormatter,
            flatList = true,
            rowActions = true,
        )
        val (tasks, items) = runBlocking {
            val (start, end) = CalendarDays.visibleDates(today, selected, firstDay, monthMode)
            val tasks = builder.fetchTasks(
                limit = null,
                rowCondition = CalendarDays.dueCondition(start, end, selected == today, zone),
            )
            val dayTasks = tasks.filter {
                CalendarDays.isShownOn(it.task.dueDate, it.task.isCompleted, selected, today, zone)
            }
            tasks to builder.buildItems(dayTasks)
        }
        val daysWithTasks = CalendarDays.daysWithTasks(
            tasks.filterNot { it.task.isCompleted }.map { it.task.dueDate },
            zone,
        )

        return RemoteViews(context.packageName, R.layout.calendar_widget).apply {
            setBackgroundColor(R.id.calendar_widget, header.backgroundColor, widgetPreferences.rowOpacity)
            setupHeader(context, id, filter, today, selected, onSurface)
            setupWeekdays(firstDay, onSurfaceVariant)
            setInt(R.id.calendar_divider, "setBackgroundColor", divider)
            val style = DayStyle(id, today, selected, daysWithTasks, accent, onSurface, onSurfaceDim)
            if (monthMode) {
                setupMonths(context, id, style, firstDay)
            } else {
                setupWeeks(context, id, style, firstDay)
            }
            setViewVisibility(R.id.calendar_week_flipper, if (monthMode) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.calendar_month_flipper, if (monthMode) View.VISIBLE else View.GONE)
            setTextColor(R.id.empty_view, onSurfaceVariant)
            setEmptyView(R.id.list_view, R.id.empty_view)
            setPendingIntentTemplate(
                R.id.list_view,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, WidgetClickActivity::class.java),
                    mutableFlags(),
                ),
            )
            RemoteViewsCompat.setRemoteAdapter(
                context = context,
                remoteViews = this,
                appWidgetId = id,
                viewId = R.id.list_view,
                items = items,
            )
        }
    }

    private fun RemoteViews.setupHeader(
        context: Context,
        id: Int,
        filter: Filter,
        today: LocalDate,
        selected: LocalDate,
        onSurface: Int,
    ) {
        val title = selected.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
            .let { if (selected.year == today.year) it else "$it ${selected.year}" }
        setTextViewText(R.id.calendar_title, title)
        setTextColor(R.id.calendar_title, onSurface)
        setTextViewText(R.id.calendar_today_number, today.dayOfMonth.toString())
        setTextColor(R.id.calendar_today_number, onSurface)
        listOf(
            R.id.calendar_today_icon,
            R.id.calendar_previous,
            R.id.calendar_next,
            R.id.calendar_add,
            R.id.calendar_settings,
        ).forEach { setColorFilter(it, onSurface) }

        setOnClickPendingIntent(R.id.calendar_today, navigation(context, id, ACTION_TODAY))
        setOnClickPendingIntent(R.id.calendar_previous, navigation(context, id, ACTION_PREVIOUS))
        setOnClickPendingIntent(R.id.calendar_next, navigation(context, id, ACTION_NEXT))
        setOnClickPendingIntent(R.id.calendar_settings, settingsIntent(context, id))
        setViewVisibility(R.id.calendar_add, if (filter.isWritable) View.VISIBLE else View.GONE)
        setOnClickPendingIntent(R.id.calendar_add, newTaskIntent(context, filter, id, selected))
    }

    private fun RemoteViews.setupWeekdays(firstDay: DayOfWeek, color: Int) {
        CalendarDays.weekdays(firstDay).forEachIndexed { index, day ->
            val viewId = WEEKDAY_IDS[index]
            setTextViewText(viewId, day.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()))
            setTextColor(viewId, color)
        }
    }

    private fun RemoteViews.setupWeeks(
        context: Context,
        widgetId: Int,
        style: DayStyle,
        firstDay: DayOfWeek,
    ) {
        val window = CalendarDays.weekWindow(style.today, style.selected, firstDay)
        val items = RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        window.starts.forEach { start ->
            val week = RemoteViews(context.packageName, R.layout.calendar_week)
            CalendarDays.weekDays(start, firstDay).forEachIndexed { index, day ->
                week.bindDay(WEEK_DAYS[index], style, day, dimmed = false)
            }
            items.addItem(start.toEpochDay(), week)
        }
        setupPages(context, widgetId, R.id.calendar_week_flipper, items.build(), window.selectedIndex)
    }

    private fun RemoteViews.setupMonths(
        context: Context,
        widgetId: Int,
        style: DayStyle,
        firstDay: DayOfWeek,
    ) {
        val window = CalendarDays.monthWindow(style.today, style.selected)
        val items = RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        window.months.forEach { month ->
            val page = RemoteViews(context.packageName, R.layout.calendar_month)
            val grid = CalendarDays.monthGrid(month, firstDay)
            MONTH_ROWS.forEachIndexed { row, rowId ->
                page.setViewVisibility(rowId, if (row < grid.size) View.VISIBLE else View.GONE)
            }
            grid.forEachIndexed { row, week ->
                week.forEachIndexed { column, day ->
                    page.bindDay(
                        MONTH_DAYS[row * 7 + column],
                        style,
                        day,
                        dimmed = YearMonth.from(day) != month,
                    )
                }
            }
            items.addItem(month.atDay(1).toEpochDay(), page)
        }
        setupPages(context, widgetId, R.id.calendar_month_flipper, items.build(), window.selectedIndex)
    }

    private fun RemoteViews.setupPages(
        context: Context,
        widgetId: Int,
        flipperId: Int,
        items: RemoteCollectionItems,
        selectedIndex: Int,
    ) {
        RemoteViewsCompat.setRemoteAdapter(
            context = context,
            remoteViews = this,
            appWidgetId = widgetId,
            viewId = flipperId,
            items = items,
        )
        setDisplayedChild(flipperId, selectedIndex)
        setPendingIntentTemplate(flipperId, broadcastTemplate(context, widgetId))
    }

    private fun RemoteViews.bindDay(views: DayViews, style: DayStyle, day: LocalDate, dimmed: Boolean) {
        setTextViewText(views.text, day.dayOfMonth.toString())
        val isToday = day == style.today
        when {
            isToday -> {
                setViewVisibility(views.bg, View.VISIBLE)
                setImageViewResource(views.bg, R.drawable.calendar_circle_filled)
                setColorFilter(views.bg, style.accent.primaryColor)
            }
            day == style.selected -> {
                setViewVisibility(views.bg, View.VISIBLE)
                setImageViewResource(views.bg, R.drawable.calendar_circle_outline)
                setColorFilter(views.bg, style.accent.primaryColor)
            }
            else -> setViewVisibility(views.bg, View.GONE)
        }
        setTextColor(
            views.text,
            when {
                isToday -> style.accent.colorOnPrimary
                dimmed -> style.onSurfaceDim
                else -> style.onSurface
            },
        )
        // Views can be reused across updates, so always set visibility explicitly
        if (day in style.daysWithTasks) {
            setViewVisibility(views.dot, View.VISIBLE)
            setColorFilter(views.dot, style.accent.primaryColor)
        } else {
            setViewVisibility(views.dot, View.INVISIBLE)
        }
        setOnClickFillInIntent(
            views.cell,
            Intent()
                .setAction(ACTION_SELECT_DAY)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, style.widgetId)
                .putExtra(EXTRA_DAY, day.toEpochDay()),
        )
    }

    private class DayStyle(
        val widgetId: Int,
        val today: LocalDate,
        val selected: LocalDate,
        val daysWithTasks: Set<LocalDate>,
        val accent: ThemeColor,
        val onSurface: Int,
        val onSurfaceDim: Int,
    )

    private class DayViews(val cell: Int, val bg: Int, val text: Int, val dot: Int)

    private fun broadcastTemplate(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            widgetId,
            Intent(context, CalendarActionReceiver::class.java),
            mutableFlags(),
        )

    private fun navigation(context: Context, widgetId: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            widgetId,
            Intent(context, CalendarActionReceiver::class.java)
                .setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun settingsIntent(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            widgetId,
            Intent(context, WidgetConfigActivity::class.java)
                .setFlags(FLAGS)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setAction("widget_settings"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun newTaskIntent(
        context: Context,
        filter: Filter,
        widgetId: Int,
        dueDay: LocalDate,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            widgetId,
            TaskIntents
                .getNewTaskIntent(
                    context,
                    filter,
                    "widget",
                    dueDay.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
                .putExtra(FINISH_AFFINITY, true)
                .setAction("new_task"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun mutableFlags(): Int =
        if (atLeastS()) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    companion object {
        private val WEEKDAY_IDS = listOf(
            R.id.calendar_weekday_0, R.id.calendar_weekday_1, R.id.calendar_weekday_2,
            R.id.calendar_weekday_3, R.id.calendar_weekday_4, R.id.calendar_weekday_5,
            R.id.calendar_weekday_6,
        )
        private val WEEK_DAYS = listOf(
            DayViews(R.id.calendar_cell_0, R.id.calendar_day_bg_0, R.id.calendar_day_text_0, R.id.calendar_day_dot_0),
            DayViews(R.id.calendar_cell_1, R.id.calendar_day_bg_1, R.id.calendar_day_text_1, R.id.calendar_day_dot_1),
            DayViews(R.id.calendar_cell_2, R.id.calendar_day_bg_2, R.id.calendar_day_text_2, R.id.calendar_day_dot_2),
            DayViews(R.id.calendar_cell_3, R.id.calendar_day_bg_3, R.id.calendar_day_text_3, R.id.calendar_day_dot_3),
            DayViews(R.id.calendar_cell_4, R.id.calendar_day_bg_4, R.id.calendar_day_text_4, R.id.calendar_day_dot_4),
            DayViews(R.id.calendar_cell_5, R.id.calendar_day_bg_5, R.id.calendar_day_text_5, R.id.calendar_day_dot_5),
            DayViews(R.id.calendar_cell_6, R.id.calendar_day_bg_6, R.id.calendar_day_text_6, R.id.calendar_day_dot_6),
        )
        private val MONTH_DAYS = listOf(
            DayViews(R.id.calendar_m_cell_0, R.id.calendar_m_bg_0, R.id.calendar_m_text_0, R.id.calendar_m_dot_0),
            DayViews(R.id.calendar_m_cell_1, R.id.calendar_m_bg_1, R.id.calendar_m_text_1, R.id.calendar_m_dot_1),
            DayViews(R.id.calendar_m_cell_2, R.id.calendar_m_bg_2, R.id.calendar_m_text_2, R.id.calendar_m_dot_2),
            DayViews(R.id.calendar_m_cell_3, R.id.calendar_m_bg_3, R.id.calendar_m_text_3, R.id.calendar_m_dot_3),
            DayViews(R.id.calendar_m_cell_4, R.id.calendar_m_bg_4, R.id.calendar_m_text_4, R.id.calendar_m_dot_4),
            DayViews(R.id.calendar_m_cell_5, R.id.calendar_m_bg_5, R.id.calendar_m_text_5, R.id.calendar_m_dot_5),
            DayViews(R.id.calendar_m_cell_6, R.id.calendar_m_bg_6, R.id.calendar_m_text_6, R.id.calendar_m_dot_6),
            DayViews(R.id.calendar_m_cell_7, R.id.calendar_m_bg_7, R.id.calendar_m_text_7, R.id.calendar_m_dot_7),
            DayViews(R.id.calendar_m_cell_8, R.id.calendar_m_bg_8, R.id.calendar_m_text_8, R.id.calendar_m_dot_8),
            DayViews(R.id.calendar_m_cell_9, R.id.calendar_m_bg_9, R.id.calendar_m_text_9, R.id.calendar_m_dot_9),
            DayViews(R.id.calendar_m_cell_10, R.id.calendar_m_bg_10, R.id.calendar_m_text_10, R.id.calendar_m_dot_10),
            DayViews(R.id.calendar_m_cell_11, R.id.calendar_m_bg_11, R.id.calendar_m_text_11, R.id.calendar_m_dot_11),
            DayViews(R.id.calendar_m_cell_12, R.id.calendar_m_bg_12, R.id.calendar_m_text_12, R.id.calendar_m_dot_12),
            DayViews(R.id.calendar_m_cell_13, R.id.calendar_m_bg_13, R.id.calendar_m_text_13, R.id.calendar_m_dot_13),
            DayViews(R.id.calendar_m_cell_14, R.id.calendar_m_bg_14, R.id.calendar_m_text_14, R.id.calendar_m_dot_14),
            DayViews(R.id.calendar_m_cell_15, R.id.calendar_m_bg_15, R.id.calendar_m_text_15, R.id.calendar_m_dot_15),
            DayViews(R.id.calendar_m_cell_16, R.id.calendar_m_bg_16, R.id.calendar_m_text_16, R.id.calendar_m_dot_16),
            DayViews(R.id.calendar_m_cell_17, R.id.calendar_m_bg_17, R.id.calendar_m_text_17, R.id.calendar_m_dot_17),
            DayViews(R.id.calendar_m_cell_18, R.id.calendar_m_bg_18, R.id.calendar_m_text_18, R.id.calendar_m_dot_18),
            DayViews(R.id.calendar_m_cell_19, R.id.calendar_m_bg_19, R.id.calendar_m_text_19, R.id.calendar_m_dot_19),
            DayViews(R.id.calendar_m_cell_20, R.id.calendar_m_bg_20, R.id.calendar_m_text_20, R.id.calendar_m_dot_20),
            DayViews(R.id.calendar_m_cell_21, R.id.calendar_m_bg_21, R.id.calendar_m_text_21, R.id.calendar_m_dot_21),
            DayViews(R.id.calendar_m_cell_22, R.id.calendar_m_bg_22, R.id.calendar_m_text_22, R.id.calendar_m_dot_22),
            DayViews(R.id.calendar_m_cell_23, R.id.calendar_m_bg_23, R.id.calendar_m_text_23, R.id.calendar_m_dot_23),
            DayViews(R.id.calendar_m_cell_24, R.id.calendar_m_bg_24, R.id.calendar_m_text_24, R.id.calendar_m_dot_24),
            DayViews(R.id.calendar_m_cell_25, R.id.calendar_m_bg_25, R.id.calendar_m_text_25, R.id.calendar_m_dot_25),
            DayViews(R.id.calendar_m_cell_26, R.id.calendar_m_bg_26, R.id.calendar_m_text_26, R.id.calendar_m_dot_26),
            DayViews(R.id.calendar_m_cell_27, R.id.calendar_m_bg_27, R.id.calendar_m_text_27, R.id.calendar_m_dot_27),
            DayViews(R.id.calendar_m_cell_28, R.id.calendar_m_bg_28, R.id.calendar_m_text_28, R.id.calendar_m_dot_28),
            DayViews(R.id.calendar_m_cell_29, R.id.calendar_m_bg_29, R.id.calendar_m_text_29, R.id.calendar_m_dot_29),
            DayViews(R.id.calendar_m_cell_30, R.id.calendar_m_bg_30, R.id.calendar_m_text_30, R.id.calendar_m_dot_30),
            DayViews(R.id.calendar_m_cell_31, R.id.calendar_m_bg_31, R.id.calendar_m_text_31, R.id.calendar_m_dot_31),
            DayViews(R.id.calendar_m_cell_32, R.id.calendar_m_bg_32, R.id.calendar_m_text_32, R.id.calendar_m_dot_32),
            DayViews(R.id.calendar_m_cell_33, R.id.calendar_m_bg_33, R.id.calendar_m_text_33, R.id.calendar_m_dot_33),
            DayViews(R.id.calendar_m_cell_34, R.id.calendar_m_bg_34, R.id.calendar_m_text_34, R.id.calendar_m_dot_34),
            DayViews(R.id.calendar_m_cell_35, R.id.calendar_m_bg_35, R.id.calendar_m_text_35, R.id.calendar_m_dot_35),
            DayViews(R.id.calendar_m_cell_36, R.id.calendar_m_bg_36, R.id.calendar_m_text_36, R.id.calendar_m_dot_36),
            DayViews(R.id.calendar_m_cell_37, R.id.calendar_m_bg_37, R.id.calendar_m_text_37, R.id.calendar_m_dot_37),
            DayViews(R.id.calendar_m_cell_38, R.id.calendar_m_bg_38, R.id.calendar_m_text_38, R.id.calendar_m_dot_38),
            DayViews(R.id.calendar_m_cell_39, R.id.calendar_m_bg_39, R.id.calendar_m_text_39, R.id.calendar_m_dot_39),
            DayViews(R.id.calendar_m_cell_40, R.id.calendar_m_bg_40, R.id.calendar_m_text_40, R.id.calendar_m_dot_40),
            DayViews(R.id.calendar_m_cell_41, R.id.calendar_m_bg_41, R.id.calendar_m_text_41, R.id.calendar_m_dot_41),
        )
        private val MONTH_ROWS = listOf(
            R.id.calendar_month_row_0,
            R.id.calendar_month_row_1,
            R.id.calendar_month_row_2,
            R.id.calendar_month_row_3,
            R.id.calendar_month_row_4,
            R.id.calendar_month_row_5,
        )
        private const val DIMMED_ALPHA = 97

        fun isMonthMode(options: Bundle): Boolean =
            CalendarDays.isMonthMode(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))

        private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
}
