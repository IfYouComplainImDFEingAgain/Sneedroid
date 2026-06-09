package st.kiwifarms.sneedroid.ui.chat

import st.kiwifarms.sneedroid.core.model.ChatAuthor
import st.kiwifarms.sneedroid.core.model.ChatMessage
import st.kiwifarms.sneedroid.data.TimelineItem

/**
 * Sample timeline for offline UI verification (debug preview only). Mirrors the design
 * pack's seed messages so the rendered result can be compared to the mockups.
 */
object SampleData {
    private var t = System.currentTimeMillis() / 1000 - 600
    private fun next(): Long { t += 60; return t }

    private fun msg(id: Long, user: String, raw: String): TimelineItem.Msg =
        TimelineItem.Msg(
            ChatMessage(
                uuid = "s$id",
                id = id,
                author = ChatAuthor(id = id, username = user),
                raw = raw,
                date = next(),
            ),
        )

    /** A pinned MOTD for the debug preview (mirrors a real `motd` frame). */
    fun motd(): ChatMessage = ChatMessage(
        uuid = "motd-sample",
        author = ChatAuthor(id = 1, username = "KenoGPT"),
        raw = "[b]Kasino schedule, extreme summer downtime edition[/b]\nThis week: Not-E3!\n" +
            "Today's Live Slop\nCytube link: https://cytu.be/r/KiwiKinoKompound\n" +
            "generate avelloons for free at https://avelloons.ddos.lgbt by @troonshine",
        date = System.currentTimeMillis() / 1000 - 3600,
    )

    fun timeline(): List<TimelineItem> = listOf(
        msg(1, "KenoGPT", "[b]Draw #4471[/b] is in — [color=green]07 · 12 · 19 · 23 · 38 · 51 · 60 · 66[/color]"),
        msg(2, "KenoGPT", "Next draw opens in [b]90s[/b]. Place your bets."),
        msg(99, "KenoGPT", "[img]https://i.ddos.lgbt/u/7cQE6w.webp[/img][br][size=60][spoiler=\"Image Info\"][heading=1]ID: 2137; Tags: lol, cow, supreme, lcs, cheese, wrong, haram, delulu, chat, and chyat; Carousel: kuote; Added By: Unknown; Date Added: Unknown[/heading][/spoiler][/size]"),
        msg(3, "spektr", "called the [b]19[/b] lmao easy money"),
        msg(4, "spektr", "robbed. absolutely robbed."),
        msg(5, "Greasy_Pete", "had 23 and 60, missed the rest by a country mile"),
        msg(6, "noodlearms", "[quote=spektr]called the 19[/quote]sure you did buddy"),
        TimelineItem.Sys(100, "noodlearms was muted for 10 minutes by a moderator."),
        msg(7, "Vivienne", "putting it all on [color=#d98b91]66[/color] this round, wish me luck [i]xx[/i]"),
        msg(8, "tugboat", "before you do that read this [url=https://example.org/keno-odds]odds breakdown[/url]"),
        msg(9, "marlin", "nah the house always wins @Vivienne, you know this"),
        msg(10, "marlin", "the math just doesn't work out, never has"),
        msg(11, "spektr", "regular cat tax [img]i.ddos.lgbt/u/7cQE6w.webp[/img]"),
        // Linked-image embed (SneedChat's auto-wrap for pasted image URLs) — must render as an image.
        msg(15, "spektr", "[url=https://i.ddos.lgbt/u/7cQE6w.webp][img]https://i.ddos.lgbt/u/7cQE6w.webp[/img][/url] and a caption after it"),
        msg(12, "tugboat", "watch this https://www.youtube.com/watch?v=dQw4w9WgXcQ lol"),
    )
}
