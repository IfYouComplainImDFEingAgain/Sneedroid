package st.kiwifarms.sneedroid.core.net

import st.kiwifarms.sneedroid.core.pow.KiwiFlareChallenge
import st.kiwifarms.sneedroid.core.pow.PowVariant
import kotlin.time.Duration.Companion.minutes

/**
 * Minimal scraping of the KiwiFarms pages. Everything the login/PoW flow needs lives as
 * attributes on the root `<html>` element, so we only parse that opening tag — no HTML
 * parser dependency required. (docs/02-protocol.md, open question #5: regex-over-root-tag.)
 */
object HtmlScrape {

    private val htmlTag = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attr = Regex("""([:\w-]+)\s*=\s*"([^"]*)"""")

    /** Attributes of the first `<html ...>` opening tag, or empty if none found. */
    fun rootHtmlAttributes(html: String): Map<String, String> {
        val tag = htmlTag.find(html)?.value ?: return emptyMap()
        return attr.findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    }

    /**
     * Extract a PoW challenge from `GET /` HTML, or null if no challenge is present.
     * Tries Tartarus (`ttrs`) first, then `sssg`.
     */
    fun parseChallenge(html: String): KiwiFlareChallenge? {
        val attrs = rootHtmlAttributes(html)
        val variant = when {
            attrs.containsKey("data-ttrs-challenge") -> PowVariant.TTRS
            attrs.containsKey("data-sssg-challenge") -> PowVariant.SSSG
            else -> return null
        }
        val prefix = if (variant == PowVariant.TTRS) "ttrs" else "sssg"
        val salt = attrs["data-$prefix-challenge"] ?: return null
        val difficulty = attrs["data-$prefix-difficulty"]?.toIntOrNull() ?: return null
        val patience = attrs["data-sssg-patience"]?.toDoubleOrNull()?.minutes ?: 5.minutes
        return KiwiFlareChallenge(salt = salt, difficulty = difficulty, variant = variant, patience = patience)
    }

    /**
     * The XenForo CSRF token. Prefers the root `<html data-csrf>` attribute, falling back
     * to a hidden `<input name="_xfToken" value="…">` (used on the two-step page).
     */
    fun csrfToken(html: String): String? =
        rootHtmlAttributes(html)["data-csrf"] ?: xfTokenInput(html)

    private val xfTokenInputRegex = Regex(
        """<input[^>]*name="_xfToken"[^>]*value="([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val xfTokenInputRegexAlt = Regex(
        """<input[^>]*value="([^"]*)"[^>]*name="_xfToken"""",
        RegexOption.IGNORE_CASE,
    )

    private fun xfTokenInput(html: String): String? =
        xfTokenInputRegex.find(html)?.groupValues?.get(1)
            ?: xfTokenInputRegexAlt.find(html)?.groupValues?.get(1)

    /** Whether [html] looks like the two-step (2FA) challenge page. */
    fun isTwoStepPage(html: String): Boolean =
        html.contains("two-step", ignoreCase = true) || html.contains("two_step", ignoreCase = true)

    /** Whether the page reports an authenticated session (`data-logged-in="true"`). */
    fun isLoggedIn(html: String): Boolean = rootHtmlAttributes(html)["data-logged-in"] == "true"

    /** The human-readable error from a failed login page, if present. */
    fun loginError(html: String): String? {
        val block = Regex(
            """<div class="blockMessage blockMessage--error[^"]*">(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1) ?: return null
        // Strip any inner tags and collapse whitespace.
        return block.replace(Regex("<[^>]+>"), "").trim().replace(Regex("\\s+"), " ").ifEmpty { null }
    }
}
