package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service

import java.util.concurrent.CopyOnWriteArrayList


@Service(Service.Level.PROJECT)
class AiUiStateService {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    private var lastAnswer: String = ""

    fun setAnswer(text: String) {
        //println("setAnswer listeners=${listeners.size}")
        lastAnswer = text
        listeners.forEach { it(text) }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        // immediately push latest state so toolwindow shows something on open
        listener(lastAnswer)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}

