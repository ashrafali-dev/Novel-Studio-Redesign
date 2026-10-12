package com.ashraf.novelstudio

import org.json.JSONObject

object SiteProfiles {
    data class Profile(
        val host: String,
        val chapterSelectors: List<String>,
        val nextSelectors: List<String>
    ) {
        fun chapterSelectorsJson() = toJson(chapterSelectors)
        fun nextSelectorsJson() = toJson(nextSelectors)
    }

    private val generic = Profile(
        "generic",
        listOf(
            "[itemprop='articleBody']", ".chapter-content", ".chapter_content",
            ".chapter-body", ".chapterBody", "#chapter-content", "#chapterContent",
            ".read-content", ".reading-content", ".novel-content", ".content-text",
            ".text-left", ".entry-content", ".reading", "article", "main"
        ),
        listOf(
            "a[rel='next']", "link[rel='next']", "[data-next-chapter]",
            ".next-chapter a", ".next_chapter a", ".nextChapter", ".chapter-next",
            "a[aria-label*='Next']", "button[aria-label*='Next']",
            "a[title*='Next']", "button[title*='Next']",
            "a[class*='next']", "button[class*='next']", "a[id*='next']"
        )
    )

    private val qidian = generic.copy(
        host = "qidian",
        chapterSelectors = listOf(
            ".read-content", ".chapter-content", ".content", ".readArea .content",
            "#chaptercontent", "[class*='chapter-content']", "[class*='read-content']",
            "article"
        ),
        nextSelectors = listOf(
            "#j_chapterNext", ".j_chapterNext", "a.j_chapterNext",
            ".chapter-control .next", ".chapter-control a[rel='next']",
            "a[rel='next']", "button[aria-label*='下一章']", "a[title*='下一章']"
        )
    )

    private val webnovel = generic.copy(
        host = "webnovel",
        chapterSelectors = listOf(".cha-content", ".chapter-content", ".chapter_content", "article", "main"),
        nextSelectors = listOf(".cha-next a", ".chapter-next", "a[rel='next']", "a[class*='next']")
    )

    private val wtr = generic.copy(
        host = "wtr-lab",
        chapterSelectors = listOf(".chapter-content", ".novel-content", ".content-text", "[class*='chapter-content']", "article", "main"),
        nextSelectors = listOf("a[rel='next']", "a.next-chapter", "a[class*='next']", "button[class*='next']")
    )

    private val novelBin = generic.copy(
        host = "novelbin",
        chapterSelectors = listOf("#chr-content", ".chr-c", ".chapter-content", ".reading-content", "article", "main"),
        nextSelectors = listOf("a#next_chap", "a.nextchap", "a[rel='next']", ".next-chapter a", "a[class*='next']")
    )

    private val boxNovel = generic.copy(
        host = "boxnovel",
        chapterSelectors = listOf(".chapter-content", ".text-left", ".reading-content", "#chapter-content", "article", "main"),
        nextSelectors = listOf("a#next_chap", ".next-chap a", "a[rel='next']", "a[class*='next']")
    )

    private val asianNovel = generic.copy(
        host = "asiannovel",
        chapterSelectors = listOf(".chapter-content", ".reading-content", ".entry-content", ".text-left", "article", "main"),
        nextSelectors = listOf("a[rel='next']", ".next-post a", ".nav-next a", "a[class*='next']")
    )

    fun forUrl(url: String): Profile {
        val host = url.lowercase()
        return when {
            "qidian.com" in host -> qidian
            "webnovel.com" in host -> webnovel
            "wtr-lab.com" in host -> wtr
            "novelbin" in host -> novelBin
            "boxnovel" in host -> boxNovel
            "asiannovel" in host -> asianNovel
            else -> generic
        }
    }

    fun selectorJson() = generic.chapterSelectorsJson()
    fun nextSelectorJson() = generic.nextSelectorsJson()

    private fun toJson(values: List<String>): String =
        "[" + values.joinToString(",") { JSONObject.quote(it) } + "]"
}
