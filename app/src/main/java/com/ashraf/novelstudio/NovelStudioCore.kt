package com.ashraf.novelstudio

import java.util.concurrent.atomic.AtomicLong

class NovelStudioCore {
    private val ids=AtomicLong(0)
    private val cache=linkedMapOf<String,String>()
    @Volatile var currentJob:ChapterJob?=null; private set
    @Volatile var translationRequested=false; private set
    fun beginJob(url:String):ChapterJob{translationRequested=false;val j=ChapterJob(ids.incrementAndGet(),url,"","",Status.EXTRACT_REQUESTED);currentJob=j;return j}
    fun setExtracted(id:Long,title:String,text:String):Boolean{val j=currentJob?:return false;if(j.id!=id||j.status==Status.INVALIDATED)return false;currentJob=j.copy(title=title,extractedText=text,status=Status.EXTRACTED);return true}
    fun markTranslationRequested(id:Long):Boolean{val j=currentJob?:return false;if(j.id!=id||j.status==Status.INVALIDATED||j.extractedText.isBlank())return false;translationRequested=true;currentJob=j.copy(status=Status.TRANSLATION_REQUESTED);return true}
    fun markCompleted(id:Long,t:String):Boolean{val j=currentJob?:return false;if(j.id!=id||j.status==Status.INVALIDATED)return false;translationRequested=false;currentJob=j.copy(translation=t,status=Status.COMPLETED);return true}
    fun invalidateCurrentJob(){translationRequested=false;currentJob=currentJob?.copy(status=Status.INVALIDATED)}
    fun isCurrentJob(id:Long)=currentJob?.let{it.id==id&&it.status!=Status.INVALIDATED}?:false
    fun cachedTranslation(url:String)=cache[url]
    fun saveTranslation(url:String,t:String){cache[url]=t}
    data class ChapterJob(val id:Long,val chapterUrl:String,val title:String,val extractedText:String,val status:Status,val translation:String="")
    enum class Status{EXTRACT_REQUESTED,EXTRACTED,TRANSLATION_REQUESTED,COMPLETED,INVALIDATED}
}
