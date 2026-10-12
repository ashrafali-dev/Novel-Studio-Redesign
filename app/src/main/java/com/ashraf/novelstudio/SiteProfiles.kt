package com.ashraf.novelstudio

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
            "article","[class*='chapter-content']","[class*='chapter_content']",
            "[class*='chapterBody']","[class*='chapter-body']","[id*='chapter-content']",
            "[id*='chapterContent']",".chapter-content",".read-content",
            ".reading-content",".novel-content",".content-text","main"
        ),
        listOf(
            "a[rel='next']","button[aria-label*='Next']","a[aria-label*='Next']",
            "button[class*='next']","a[class*='next']","a[id*='next']",
            "[data-next-chapter]",".next-chapter a",".next_chapter a",
            "a[href*='next']"
        )
    )

    private val qidian = generic.copy(
        host = "qidian",
        chapterSelectors = listOf(
            ".chapter-content",".read-content",".content","article",
            "[class*='chapter-content']","[class*='read-content']"
        ),
        nextSelectors = listOf(
            ".j_chapterNext","a#j_chapterNext","a[href*='/chapter/'] + a",
            ".chapter-control a.next","a.next"
        )
    )

    private val webnovel = generic.copy(
        host = "webnovel",
        chapterSelectors = listOf(".cha-content",".chapter-content","article","main"),
        nextSelectors = listOf(".cha-next a","a[rel='next']","a[class*='next']")
    )

    private val wtr = generic.copy(
        host = "wtr-lab",
        chapterSelectors = listOf(".chapter-content",".novel-content",".content-text","article","main"),
        nextSelectors = listOf("a[rel='next']","a.next-chapter","a[class*='next']")
    )

    fun forUrl(url: String): Profile {
        val u = url.lowercase()
        return when {
            "qidian.com" in u -> qidian
            "webnovel.com" in u -> webnovel
            "wtr-lab.com" in u -> wtr
            else -> generic
        }
    }

    fun selectorJson() = generic.chapterSelectorsJson()
    fun nextSelectorJson() = generic.nextSelectorsJson()

    private fun toJson(list: List<String>) =
        "[" + list.joinToString(",") { JSONObjectQuote.quote(it) } + "]"

    private object JSONObjectQuote {
        fun quote(value: String): String =
            org.json.JSONObject.quote(value)
    }
}
