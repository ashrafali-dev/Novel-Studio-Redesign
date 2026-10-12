package com.ashraf.novelstudio

import java.util.concurrent.atomic.AtomicLong

/** Browser-engine-independent state for chapter extraction and translation jobs. */
class NovelStudioCore {
    private val ids = AtomicLong(0)
    private val cache = linkedMapOf<String, String>()

    @Volatile var currentJob: ChapterJob? = null
        private set

    fun beginJob(chapterUrl: String): ChapterJob {
        val job = ChapterJob(ids.incrementAndGet(), chapterUrl, ChapterJob.Status.EXTRACT_REQUESTED)
        currentJob = job
        return job
    }

    fun markTranslationRequested(jobId: Long): Boolean {
        val job = currentJob ?: return false
        if (job.id != jobId || job.status == ChapterJob.Status.INVALIDATED) return false
        currentJob = job.copy(status = ChapterJob.Status.TRANSLATION_REQUESTED)
        return true
    }

    fun invalidateCurrentJob() { currentJob = currentJob?.copy(status = ChapterJob.Status.INVALIDATED) }
    fun isCurrentJob(jobId: Long): Boolean = currentJob?.let { it.id == jobId && it.status != ChapterJob.Status.INVALIDATED } ?: false
    fun cachedTranslation(url: String): String? = cache[url]
    fun saveTranslation(url: String, translation: String) { cache[url] = translation }

    data class ChapterJob(val id: Long, val chapterUrl: String, val status: Status) {
        enum class Status { EXTRACT_REQUESTED, TRANSLATION_REQUESTED, COMPLETED, INVALIDATED }
    }
}
