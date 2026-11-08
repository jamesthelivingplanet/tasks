package org.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.tasks.TaskListTest

class RowConditionTest : TaskListTest() {
    private suspend fun rows(condition: String?) = taskDao.fetchTasks(
        TaskListQuery.getQuery(preferences(), filter, rowCondition = condition)
    )

    private suspend fun fixture() {
        val parent = newTask(PARENT)
        newTask(CHILD, parent = parent.id, dueDate = IN_RANGE)
        val dated = newTask("dated-parent", dueDate = IN_RANGE)
        newTask("dated-child-undated", parent = dated.id)
        newTask("out", dueDate = OUT_OF_RANGE)
        newTask("undated")
        newTask(DONE, completed = true, dueDate = IN_RANGE)
    }

    @Test
    fun withoutAConditionTheQueryHasNoWhereOnTheFinalSelect() {
        val query = TaskListQuery.getQuery(preferences(), filter)

        assertFalse(query, query.contains("tasks.dueDate >="))
    }

    @Test
    fun theConditionKeepsExactlyTheMatchingRowsOfTheFullQuery() = runBlocking {
        fixture()
        val condition = "(tasks.dueDate >= $IN_RANGE AND tasks.dueDate < $OUT_OF_RANGE)"

        val expected = rows(null).filter { it.task.dueDate in IN_RANGE until OUT_OF_RANGE }
        val actual = rows(condition)

        assertEquals(expected.map { it.title }, actual.map { it.title })
        assertEquals(expected.map { it.indent }, actual.map { it.indent })
        assertEquals(expected.map { it.children }, actual.map { it.children })
    }

    @Test
    fun aSubtaskInRangeIsKeptEvenThoughItsParentIsNot() = runBlocking {
        fixture()

        val titles = rows("tasks.dueDate = $IN_RANGE").map { it.title }

        assertEquals(true, CHILD in titles)
        assertEquals(false, PARENT in titles)
    }

    companion object {
        private const val IN_RANGE = 1_700_000_000_000L
        private const val OUT_OF_RANGE = 1_800_000_000_000L
    }
}
