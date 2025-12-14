package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service
import java.util.concurrent.CopyOnWriteArrayList

data class EmailPayload(
    val question: String,
    val filePath: String?,
    val selectedSnippet: String?,
    val name: String?,
    val email: String?,
    val originPath: String?,
    val startLine: Int,
    val endLine: Int,
)

data class EmailDraft(
    val to: String,
    val subject: String,
    val bodyMarkdown: String
)


@Service(Service.Level.PROJECT)
class AiUiStateService {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile private var lastAnswer: String = ""
    @Volatile private var lastEmailPayload: EmailPayload? = null

    @Volatile private var lastDraft: EmailDraft? = null
    fun setDraft(d: EmailDraft) { lastDraft = d }
    fun getDraft(): EmailDraft? = lastDraft

    fun setResult(answer: String, payload: EmailPayload) {
        lastAnswer = answer
        lastEmailPayload = payload
        listeners.forEach { it(answer) }
    }

    fun getLastEmailPayload(): EmailPayload? = lastEmailPayload
    fun getLastAnswer(): String = lastAnswer

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        listener(lastAnswer)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
