package org.jetbrains.plugins.template.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import org.jetbrains.plugins.template.services.AiRequest
import org.jetbrains.plugins.template.services.OpenAiService

class TestOpenAiAction : AnAction("Test OpenAI (Temp)") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<OpenAiService>()

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                val req = AiRequest(
                    question = "Explain what this function does and suggest improvements.",
                    selectedSnippet = """
                        fun add(a: Int, b: Int): Int {
                            return a + b
                        }
                    """.trimIndent(),
                    filePath = "src/main/kotlin/Demo.kt",
                    fileText = """
                        package demo
                        fun add(a: Int, b: Int): Int {
                            return a + b
                        }
                    """.trimIndent(),
                    languageHint = "kotlin"
                )

                val answer = service.ask(req)

                NotificationGroupManager.getInstance()
                    .getNotificationGroup("OpenAI Test")
                    .createNotification("OpenAI response", answer.take(800), NotificationType.INFORMATION)
                    .notify(project)
            },
            "Calling OpenAI…",
            true,
            project
        )
    }
}