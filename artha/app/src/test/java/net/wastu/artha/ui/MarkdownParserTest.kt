package net.wastu.artha.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun parsesHeadingsAndDividers() {
        val md = """
            # Heading 1
            ## Heading 2
            ---
            ### Heading 3
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(4, blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "Heading 1"), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, "Heading 2"), blocks[1])
        assertEquals(MarkdownBlock.Divider, blocks[2])
        assertEquals(MarkdownBlock.Heading(3, "Heading 3"), blocks[3])
    }

    @Test
    fun parsesBulletAndNumberedLists() {
        val md = """
            - First bullet
            - Second bullet
            1. First step
            2. Second step
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(4, blocks.size)
        assertEquals(MarkdownBlock.BulletItem(0, "First bullet"), blocks[0])
        assertEquals(MarkdownBlock.BulletItem(0, "Second bullet"), blocks[1])
        assertEquals(MarkdownBlock.NumberedItem("1", "First step"), blocks[2])
        assertEquals(MarkdownBlock.NumberedItem("2", "Second step"), blocks[3])
    }

    @Test
    fun parsesBlockquotesAndCodeBlocks() {
        val md = """
            > Important note on budget pacing.
            ```
            code block content
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Blockquote("Important note on budget pacing."), blocks[0])
        assertEquals(MarkdownBlock.CodeBlock("code block content"), blocks[1])
    }

    @Test
    fun parseInlineMarkdownPreservesCurrencyFormats() {
        val text = "Spent **Rp1.228.265** on `Shopee` and *Restaurant*."
        val annotated = parseInlineMarkdown(text)
        assertEquals("Spent Rp1.228.265 on  Shopee  and Restaurant.", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
