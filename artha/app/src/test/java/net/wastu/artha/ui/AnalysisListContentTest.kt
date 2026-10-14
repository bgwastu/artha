package net.wastu.artha.ui

import org.junit.Assert.*
import org.junit.Test

class AnalysisListContentTest {
    @Test fun findingsAndRecommendationsRenderAsSeparateListsWithoutMarkdownMarkers() {
        val sections = analysisSections("- First **finding**\n- Second finding\n\n### Recommendations\n- First action\n- Second action")
        assertEquals(2, sections.size)
        assertNull(sections[0].title)
        assertEquals(listOf("First **finding**", "Second finding"), sections[0].items)
        assertEquals("Recommendations", sections[1].title)
        assertEquals(listOf("First action", "Second action"), sections[1].items)
        assertEquals("First finding", parseInlineMarkdown(sections[0].items[0]).text)
    }

    @Test fun savedNumberedAndLegacyParagraphResultsStayReadable() {
        val sections = analysisSections("Older saved finding\n\nRECOMMENDATIONS:\n1. First action\n2. Second action")
        assertEquals(listOf("Older saved finding"), sections[0].items)
        assertEquals("Recommendations", sections[1].title)
        assertEquals(listOf("First action", "Second action"), sections[1].items)
        assertTrue(analysisSections("").isEmpty())
    }
}
