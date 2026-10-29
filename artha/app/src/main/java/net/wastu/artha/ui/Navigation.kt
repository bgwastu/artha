package net.wastu.artha.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver

/** Full-screen pages opened above the three tabs. */
internal enum class Page { SOURCES, CATEGORIES, AI, REMOTE, DATA, REVIEW }

/** Saveable back stack for [Page]s; empty means the tab content is showing. */
internal class PageStack(initial: List<Page> = emptyList()) {
    private val stack = mutableStateListOf<Page>().apply { addAll(initial) }

    val top: Page? get() = stack.lastOrNull()

    fun open(page: Page) { if (top != page) stack.add(page) }

    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    companion object {
        val Saver = listSaver<PageStack, String>(
            save = { it.stack.map(Page::name) },
            restore = { names -> PageStack(names.mapNotNull { name -> Page.entries.firstOrNull { it.name == name } }) },
        )
    }
}
