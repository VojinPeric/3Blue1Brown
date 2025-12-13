package org.jetbrains.plugins.template.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import org.jetbrains.plugins.template.services.AiRequest
import org.jetbrains.plugins.template.services.OpenAiService

class AskOpenAiFromEditorAction : AnAction("Ask OpenAI (from selection)") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (editor == null || psiFile == null) {
            notify(project, "Open a file in the editor first.", NotificationType.WARNING)
            return
        }

        val question = Messages.showInputDialog(
            project,
            "Ask a question about the selected code / file:",
            "Ask OpenAI",
            Messages.getQuestionIcon()
        )?.trim()

        if (question.isNullOrBlank()) return

        // Collect editor/file data safely
        val selectedSnippet = runReadAction {
            val sel = editor.selectionModel
            if (sel.hasSelection()) sel.selectedText else null
        }

        val fileText = runReadAction { psiFile.text }
        val filePath = runReadAction { psiFile.virtualFile?.path }
        val languageHint = runReadAction { psiFile.language.id }

        val req = AiRequest(
            question = question,
            selectedSnippet = selectedSnippet,
            filePath = filePath,
            fileText = fileText,
            languageHint = languageHint
        )

        val openAi = project.service<OpenAiService>()

        object : Task.Backgroundable(project, "Calling OpenAI…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val answer = try {
                    openAi.ask(req)
                } catch (t: Throwable) {
                    "Error: ${t.message ?: t.javaClass.simpleName}"
                }

                notify(project, answer.take(1500), NotificationType.INFORMATION)
            }
        }.queue()
    }

    private fun notify(project: com.intellij.openapi.project.Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("OpenAI")
            .createNotification("OpenAI", text, type)
            .notify(project)
    }
}
