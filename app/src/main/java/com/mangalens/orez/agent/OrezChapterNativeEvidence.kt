package com.mangalens.orez.agent

import com.mangalens.core.translation.ChapterTranslationTask

internal object OrezChapterNativeEvidence {
    /** Metadata is scope proof only; awaiting validation never proves completion. */
    fun metadataReceipt(task: ChapterTranslationTask): OrezChapterReceipt {
        require(task.requestedPages == null) { "This workflow requires its captured whole-chapter scope." }
        val chapter = OrezChapterSourceEvidence.snapshot(task.chapterId, task.title,
            task.pages.map { OrezChapterSource(it.index, it.sourcePath, it.sourceSha256) })
        return OrezChapterReceipt(task.id, task.generation, task.ownerRequestId, chapter, task.config.orezChapterOptions(),
            OrezNativeChapterStatus.valueOf(task.status.name), task.completedPages, task.pages.sumOf { it.lettering.size }, task.error)
    }

    fun receipt(task: ChapterTranslationTask): OrezChapterReceipt {
        require(!task.validationPending) { "Native translation files are still awaiting validation." }
        return metadataReceipt(task)
    }
}
