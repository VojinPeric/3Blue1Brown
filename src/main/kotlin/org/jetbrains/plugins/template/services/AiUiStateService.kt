package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service
import java.util.concurrent.CopyOnWriteArrayList

data class EmailPayload(
    val question: String,
    val filePath: String?,
    val selectedSnippet: String?,
    val name: String?,
    val email: String?
)

@Service(Service.Level.PROJECT)
class AiUiStateService {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile private var lastAnswer: String = ""
    @Volatile private var lastEmailPayload: EmailPayload? = null


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
