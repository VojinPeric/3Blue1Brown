package org.jetbrains.plugins.template.actions

import com.intellij.openapi.vfs.VirtualFile
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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.plugins.template.services.AiUiStateService
import git4idea.GitUtil
import git4idea.repo.GitRepositoryManager
import git4idea.repo.GitRepository
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler



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

        // --- New: Git owner lookup ---
        if (!selectedSnippet.isNullOrBlank()) {
            val virtualFile = psiFile.virtualFile
            val document = editor.document
            val selModel = editor.selectionModel
            val startOffset = selModel.selectionStart
            val endOffset = selModel.selectionEnd

            val startLine = (document.getLineNumber(startOffset) + 1)
            val endLine = (document.getLineNumber(endOffset - 1) + 1)
            println ("line interval: $startLine $endLine" )

            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Getting Git author...", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(virtualFile)
                        if (repository != null) {
                            val authorInfo = getGitAuthorForLines(project, repository, virtualFile, startLine, endLine)
                            println("Git author of selected snippet: $authorInfo")
                        }
                    } catch (ex: Exception) {
                        println("Error getting Git author: ${ex.message}")
                    }
                }
            }.queue()
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

                val ui = project.service<AiUiStateService>()
                ui.setAnswer(answer)

                ApplicationManager.getApplication().invokeLater {
                    ToolWindowManager.getInstance(project)
                        .getToolWindow("MyToolWindow")
                        ?.show()
                }
            }
        }.queue()
    }

    private fun notify(project: com.intellij.openapi.project.Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("OpenAI")
            .createNotification("OpenAI", text, type)
            .notify(project)
    }

    /** Utility function to get Git author for a given line range */
    private fun getGitAuthorForLines(
        project: com.intellij.openapi.project.Project,
        repository: GitRepository,
        file: VirtualFile,
        startLine: Int,
        endLine: Int
    ): String {

        val handler = GitLineHandler(project, repository.root, GitCommand.BLAME)
        handler.setSilent(true)
        handler.addParameters("-e", "-L", "$startLine,$endLine", file.path)
        val resultEmail = Git.getInstance().runCommand(handler)

        val outputEmail = resultEmail.output  // List<String> - already available

        val handler2 = GitLineHandler(project, repository.root, GitCommand.BLAME)
        handler2.setSilent(true)
        handler2.addParameters("-L", "$startLine,$endLine", file.path)
        val resultName = Git.getInstance().runCommand(handler2)

        val outputName = resultName.output
        println("raw output email: $outputEmail")
        println("raw output email: $outputName")
        val firstLineEmail = outputEmail.firstOrNull()
        val firstLineName = outputName.firstOrNull()
        println("first line: $firstLineEmail")
        println("first line: $firstLineName")
        // Git output looks like: commitHash (Author Name YYYY-MM-DD ...) lineContent
        if (firstLineEmail != null && firstLineName != null) {
            val regexEmail = Regex("<([^>]+)>")
            val regexName = Regex("\\(([^<>\\s][^)]+?)\\s+\\d{4}")
            val matchEmail = regexEmail.find(firstLineEmail)
            val matchName = regexName.find(firstLineName)
            if (matchEmail != null) {
                val email = matchEmail!!.groupValues[1].trim()
                val name = matchName!!.groupValues[1].trim()
                return "$name $email"
            }
        }
        return "Unknown author"
    }
}
