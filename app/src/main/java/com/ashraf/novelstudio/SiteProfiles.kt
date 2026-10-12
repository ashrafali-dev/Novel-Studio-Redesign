package com.ashraf.novelstudio

object SiteProfiles {
    fun selectorJson()="""["article","[class*='chapter-content']","[class*='chapter_content']","[class*='chapterBody']","[class*='chapter-body']","[id*='chapter-content']","[id*='chapterContent']",".chapter-content",".read-content",".reading-content",".novel-content",".content-text","main"]"""
    fun nextSelectorJson()="""["a[rel='next']","button[aria-label*='Next']","a[aria-label*='Next']","button[class*='next']","a[class*='next']","a[id*='next']","[data-next-chapter]",".next-chapter a",".next_chapter a"]"""
}