package net.wastu.artha.data.sync

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** What Bank Jago's hidden-display UI dumps and `dumpsys` output tell us, kept free of root and Android so it can be tested. */
object JagoScreens {
    /** "Enter your PIN", or its Indonesian "Masukkan PIN ...", as a label inside one attribute. */
    private val PIN_PROMPT = Regex("(?i)\\b(enter|masukkan|input)\\b[^\"<>]{0,24}\\bpin\\b")

    fun hasPinPrompt(dump: String): Boolean = PIN_PROMPT.containsMatchIn(dump)

    /** Whether the dump shows a real screen (not an empty hierarchy) that is not the PIN prompt. */
    fun isPastPinScreen(dump: String): Boolean = dump.contains("<node") && !hasPinPrompt(dump)

    class ScreenNode(val label: String, val className: String, val packageName: String, val centerX: Int, val centerY: Int)

    private val BOUNDS = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    fun nodes(xml: String): List<ScreenNode> = runCatching {
        val clean = (if (xml.startsWith("ok ")) xml.substring(3) else xml).trim()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(clean)))
        val list = doc.getElementsByTagName("node")
        (0 until list.length).mapNotNull { i ->
            val element = list.item(i) as? Element ?: return@mapNotNull null
            val label = element.getAttribute("text").ifBlank { element.getAttribute("content-desc") }
            val bounds = BOUNDS.matchEntire(element.getAttribute("bounds"))?.destructured
            val (x1, y1, x2, y2) = bounds?.toList()?.map(String::toInt) ?: listOf(0, 0, 0, 0)
            ScreenNode(label.trim(), element.getAttribute("class"), element.getAttribute("package"), (x1 + x2) / 2, (y1 + y2) / 2)
        }
    }.getOrDefault(emptyList())

    /** Buttons that lead on to the PIN keypad: tapped first. */
    private val USE_PIN = setOf("use pin", "use pin instead", "use pin code", "enter pin", "use passcode",
        "gunakan pin", "pakai pin", "gunakan pin saja", "masukkan pin")

    /** Buttons that only close an offer or notice. Never "OK", "Allow" or "Update", which agree to something. */
    private val DISMISS = setOf("not now", "later", "maybe later", "skip", "close", "dismiss", "cancel", "no thanks",
        "nanti", "nanti saja", "lain kali", "lewati", "tutup", "batal", "tidak sekarang", "tidak, terima kasih")

    /** Where to tap to get past a benign prompt in front of the PIN keypad, or null when there is none. */
    fun dismissTarget(dump: String): Pair<Int, Int>? {
        val buttons = nodes(dump).filter { it.label.isNotEmpty() && it.centerX > 0 && it.centerY > 0 }
        fun find(labels: Set<String>) = buttons.firstOrNull { normalize(it.label) in labels }
        return (find(USE_PIN) ?: find(DISMISS))?.let { it.centerX to it.centerY }
    }

    private fun normalize(label: String) = label.lowercase().trim().trim('.', '!', ' ')

    /**
     * A privacy-masked outline of a screen: every label with digits turned into `#`, e-mail-like text
     * dropped, long text cut. Names written in letters can still appear, so it is only recorded when the
     * PIN screen was not found, which is before anything personal is on show.
     */
    fun signature(dump: String): String {
        val nodes = nodes(dump)
        if (nodes.isEmpty()) return "empty screen"
        val labels = nodes.map { maskLabel(it.label) }.filter { it.isNotEmpty() }.distinct().take(MAX_LABELS)
        val packages = nodes.map { it.packageName }.filter { it.isNotEmpty() }.distinct()
        return "nodes=${nodes.size} packages=${packages.joinToString(",")} labels=${labels.joinToString(" | ")}".take(MAX_SIGNATURE)
    }

    fun maskLabel(raw: String): String =
        raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word ->
            when {
                '@' in word -> "<email>"
                word.any(Char::isDigit) -> word.map { if (it.isDigit()) '#' else it }.joinToString("")
                else -> word.take(24)
            }
        }.take(60)

    /** How Android says a process ended, from `dumpsys activity exit-info <package>`. */
    data class ExitRecord(val reason: String, val description: String?, val timestamp: String?) {
        override fun toString() = listOfNotNull(reason, description?.let { "\"$it\"" }, timestamp?.let { "at $it" }).joinToString(" ")
    }

    /** The newest exit record: the dump lists newest first. Null when it has none or the format is unfamiliar. */
    fun parseExitInfo(lines: List<String>): ExitRecord? {
        val text = lines.joinToString("\n")
        val reason = Regex("reason=(\\d+) \\(([^)]*)\\)").find(text) ?: return null
        val description = Regex("description=([^\\n]*)").find(text)?.groupValues?.get(1)
            ?.substringBefore(" state=")?.trim()?.takeIf { it.isNotEmpty() }
        val timestamp = Regex("timestamp=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})").find(text)?.groupValues?.get(1)
        return ExitRecord("${reason.groupValues[2].trim()} (code ${reason.groupValues[1]})", description, timestamp)
    }

    private const val MAX_LABELS = 25
    private const val MAX_SIGNATURE = 700
}
