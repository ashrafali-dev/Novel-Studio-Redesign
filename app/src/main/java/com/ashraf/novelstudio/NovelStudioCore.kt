package com.ashraf.novelstudio

import java.util.concurrent.atomic.AtomicLong

class NovelStudioCore {
    private val ids = AtomicLong(0)
    private val cache = linkedMapOf<String, String>()

    @Volatile var currentJob: ChapterJob? = null
        private set
    @Volatile var translationRequested = false
        private set

    fun beginJob(url: String): ChapterJob {
        translationRequested = false
        val job = ChapterJob(ids.incrementAndGet(), url, "", "", Status.EXTRACT_REQUESTED)
        currentJob = job
        return job
    }

    fun setExtracted(id: Long, title: String, text: String): Boolean {
        val job = currentJob ?: return false
        if (job.id != id || job.status == Status.INVALIDATED) return false
        currentJob = job.copy(title = title, extractedText = text, status = Status.EXTRACTED)
        return true
    }

    fun markTranslationRequested(id: Long): Boolean {
        val job = currentJob ?: return false
        if (job.id != id || job.status == Status.INVALIDATED || job.extractedText.isBlank()) return false
        translationRequested = true
        currentJob = job.copy(status = Status.TRANSLATION_REQUESTED)
        return true
    }

    fun cancelTranslationRequest(id: Long): Boolean {
        val job = currentJob ?: return false
        if (job.id != id || job.status == Status.INVALIDATED) return false
        translationRequested = false
        if (job.status == Status.TRANSLATION_REQUESTED) {
            currentJob = job.copy(status = Status.EXTRACTED)
        }
        return true
    }

    fun markCompleted(id: Long, translation: String): Boolean {
        val job = currentJob ?: return false
        if (job.id != id || job.status == Status.INVALIDATED) return false
        translationRequested = false
        currentJob = job.copy(translation = translation, status = Status.COMPLETED)
        return true
    }

    fun invalidateCurrentJob() {
        translationRequested = false
        currentJob = currentJob?.copy(status = Status.INVALIDATED)
    }

    fun isCurrentJob(id: Long): Boolean =
        currentJob?.let { it.id == id && it.status != Status.INVALIDATED } ?: false

    fun cachedTranslation(url: String): String? = cache[url]
    fun saveTranslation(url: String, translation: String) { cache[url] = translation }

    data class ChapterJob(
        val id: Long,
        val chapterUrl: String,
        val title: String,
        val extractedText: String,
        val status: Status,
        val translation: String = ""
    )

    enum class Status { EXTRACT_REQUESTED, EXTRACTED, TRANSLATION_REQUESTED, COMPLETED, INVALIDATED }
}
