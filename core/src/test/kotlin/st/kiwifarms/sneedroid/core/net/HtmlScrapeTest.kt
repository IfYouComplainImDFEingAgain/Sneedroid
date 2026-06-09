package st.kiwifarms.sneedroid.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.kiwifarms.sneedroid.core.pow.PowVariant

class HtmlScrapeTest {

    @Test
    fun `parses an sssg challenge with patience`() {
        val html = """<!DOCTYPE html><html id="sssg" data-sssg-challenge="abc123" data-sssg-difficulty="20" data-sssg-patience="3"><head></head></html>"""
        val c = HtmlScrape.parseChallenge(html)!!
        assertEquals("abc123", c.salt)
        assertEquals(20, c.difficulty)
        assertEquals(PowVariant.SSSG, c.variant)
        assertEquals(3.0, c.patience.inWholeSeconds / 60.0)
    }

    @Test
    fun `parses a ttrs challenge and prefers it`() {
        val html = """<html id="ttrs" data-ttrs-challenge="ttsalt" data-ttrs-difficulty="18"></html>"""
        val c = HtmlScrape.parseChallenge(html)!!
        assertEquals("ttsalt", c.salt)
        assertEquals(18, c.difficulty)
        assertEquals(PowVariant.TTRS, c.variant)
    }

    @Test
    fun `no challenge returns null`() {
        assertNull(HtmlScrape.parseChallenge("""<html data-logged-in="true"></html>"""))
        assertNull(HtmlScrape.parseChallenge("no html here"))
    }

    @Test
    fun `reads csrf and logged-in state`() {
        val html = """<html data-csrf="tok-9" data-logged-in="true" lang="en"></html>"""
        assertEquals("tok-9", HtmlScrape.csrfToken(html))
        assertTrue(HtmlScrape.isLoggedIn(html))
    }

    @Test
    fun `extracts login error text stripped of tags`() {
        val html = """<html data-logged-in="false"></html><body><div class="blockMessage blockMessage--error blockMessage--iconic">Incorrect <b>password</b>.</div></body>"""
        assertEquals("Incorrect password.", HtmlScrape.loginError(html))
    }

    @Test
    fun `no login error returns null`() {
        assertNull(HtmlScrape.loginError("""<html></html>"""))
    }
}
