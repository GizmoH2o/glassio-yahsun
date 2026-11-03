package com.glasslauncher.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone page's script: one broken string and the browser drops all of it (an apostrophe in "TV's" did, and the
 * remote, typing and search did nothing from 2.2.0 to 2.5.0).
 */
class PhonePageTest {
    private fun script(page: String) = Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(page).joinToString("\n") { it.groupValues[1] }

    /** Lines of [js] with a quoted string still open at their end: "line: text". */
    private fun unclosed(js: String): List<String> = js.lines().mapIndexedNotNull { n, line ->
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                // A comment to the end of the line, outside strings (not "//" inside a regex like /^(https?:\/\/...)/).
                quote == null && c == '/' && line.getOrNull(i + 1) == '/' && line.getOrNull(i - 1) != '\\' && line.getOrNull(i - 1) != ':' -> break
            }
            i++
        }
        if (quote != null) "${n + 1}: ${line.take(120)}" else null
    }

    @Test fun `every string in the page's script is closed`() {
        val js = script(PhoneServer.PAGE)
        assertTrue("the page has its script", js.contains("function k(key)"))
        assertEquals(emptyList<String>(), unclosed(js))
    }

    @Test fun `the check finds an apostrophe that ends a string`() {
        assertEquals(1, unclosed("say('Saved in the TV's Downloads',true)").size)
        assertEquals(0, unclosed("say('Saved in the TV’s Downloads',true);var u=x.replace(/^(https?:\\/\\/[^\\/]+).*$/,'$1')").size)
    }

    @Test fun `the remote, typing and search are wired to the TV`() {
        val page = PhoneServer.PAGE
        for (k in listOf("up", "down", "left", "right", "ok", "back", "home", "playpause", "volup", "voldown", "mute"))
            assertTrue("button $k", page.contains("onclick=\"k('$k')\""))
        assertTrue(page.contains("send('/api/text',"))
        assertTrue(page.contains("send('/api/search',"))
    }
}
