package net.wastu.artha.core.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogFileTest {
    @get:Rule val folder = TemporaryFolder()

    private fun entry(n: Int, message: String = "event $n") = LogEntry(1_000L + n, 'I', "Test", message, mapOf("n" to n.toString()))

    @Test fun entriesComeBackInOrderWithTheirFields() {
        val log = LogFile(File(folder.root, "activity.log"))
        (1..3).forEach { log.append(entry(it)) }
        assertEquals((1..3).map { entry(it) }, log.read())
        assertEquals(listOf(entry(3)), log.read(limit = 1))
    }

    @Test fun aLineWithNewlinesAndQuotesStaysOneEntry() {
        val log = LogFile(File(folder.root, "activity.log"))
        val tricky = LogEntry(5, 'W', "Test", "line one\nline \"two\" {braces}", mapOf("k" to "a\nb"))
        log.append(tricky)
        log.append(entry(6))
        assertEquals(listOf(tricky, entry(6)), log.read())
    }

    @Test fun theFileRotatesAndKeepsAtMostOneBackup() {
        val file = File(folder.root, "activity.log")
        val log = LogFile(file, maxBytes = 400)
        (1..40).forEach { log.append(entry(it)) }
        val kept = log.read().map { it.timeMillis - 1_000L }
        assertEquals(40L, kept.last())
        assertEquals("only the newest entries survive in order", kept.sorted(), kept)
        assertTrue(kept.size in 2 until 40)
        assertTrue(file.length() <= 400)
        assertTrue(File(file.path + ".1").exists())
        assertTrue(folder.root.listFiles().orEmpty().size == 2)
    }

    @Test fun clearRemovesBothFiles() {
        val file = File(folder.root, "activity.log")
        val log = LogFile(file, maxBytes = 200)
        (1..20).forEach { log.append(entry(it)) }
        log.clear()
        assertTrue(log.read().isEmpty())
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun damagedLinesAreSkippedInsteadOfBreakingTheLog() {
        val file = File(folder.root, "activity.log")
        val log = LogFile(file)
        log.append(entry(1))
        file.appendText("{not json\n\n")
        log.append(entry(2))
        assertEquals(listOf(entry(1), entry(2)), log.read())
        assertNull(LogEntry.parse("garbage"))
    }
}
