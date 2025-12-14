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
import org.jetbrains.plugins.template.services.EmailPayload
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import git4idea.GitUtil
import git4idea.repo.GitRepositoryManager
import git4idea.repo.GitRepository
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler



class AskOpenAiFromEditorAction : AnAction("Athena Deescalate") {

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
            "Athena Proxy",
            Messages.getQuestionIcon()
        )?.trim()

        if (question.isNullOrBlank()) return

        // Collect editor/file data safely
        val selectedSnippet = runReadAction {
            val sel = editor.selectionModel
            if (sel.hasSelection()) sel.selectedText else null
        }

        // --- New: Git owner lookup ---
        if (selectedSnippet.isNullOrBlank()) return

        val virtualFile = psiFile.virtualFile
        val document = editor.document
        val selModel = editor.selectionModel
        val startOffset = selModel.selectionStart
        val endOffset = selModel.selectionEnd

        val startLine = (document.getLineNumber(startOffset) + 1)
        val endLine = (document.getLineNumber(endOffset - 1) + 1)
        var authorInfo: Sequence<String>  = sequenceOf()
        var repositoryPath: String? = null

        val fileText = runReadAction { psiFile.text }
        val filePath = runReadAction {
            val vf = psiFile.virtualFile ?: return@runReadAction null
            val basePath = project.basePath ?: return@runReadAction null
            val baseVf = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@runReadAction null
            val rel = VfsUtilCore.getRelativePath(vf, baseVf, '/') ?: return@runReadAction null
            "${project.name}/$rel"
        }
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

                try {
                    val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(virtualFile)
                    if (repository != null) {
                        authorInfo = getGitAuthorForLines(project, repository, virtualFile, startLine, endLine)
                        val origin = repository.remotes.firstOrNull { it.name == "origin" }
                            ?: repository.remotes.firstOrNull()

                        val remoteUrl = origin?.firstUrl
                        if (remoteUrl != null)
                            repositoryPath = extractGithubRepoPath(remoteUrl)
                        else
                            repositoryPath = null

                        println("Git author of selected snippet: $authorInfo")
                    }
                } catch (ex: Exception) {
                    println("Error getting Git author: ${ex.message}")
                }

                val ui = project.service<AiUiStateService>()
                ui.setResult(
                    answer = answer,
                    payload = EmailPayload(
                        question = question,
                        filePath = filePath,
                        selectedSnippet = selectedSnippet,
                        name = authorInfo.elementAtOrNull(0),
                        email = authorInfo.elementAtOrNull(1),
                        originPath = repositoryPath,
                        startLine = startLine,
                        endLine = endLine,
                    )
                )

                ApplicationManager.getApplication().invokeLater {
                    ToolWindowManager.getInstance(project)
                        .getToolWindow("Athena")
                        ?.show()
                }
            }
        }.queue()
    }

    private fun notify(project: com.intellij.openapi.project.Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Athena")
            .createNotification("Athena", text, type)
            .notify(project)
    }

    /** Utility function to get Git author for a given line range */
    private fun getGitAuthorForLines(
        project: com.intellij.openapi.project.Project,
        repository: GitRepository,
        file: VirtualFile,
        startLine: Int,
        endLine: Int
    ): Sequence<String> {

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
                return sequenceOf(name, email)
            }
        }
        throw IllegalArgumentException("Unkown author")
    }

    fun extractGithubRepoPath(url: String): String? {
        return when {
            url.startsWith("git@github.com:") ->
                url.removePrefix("git@github.com:")
                    .removeSuffix(".git")

            url.startsWith("https://github.com/") ->
                url.removePrefix("https://github.com/")
                    .removeSuffix(".git")

            else -> null
        }
    }
}
