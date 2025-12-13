package org.jetbrains.plugins.template.toolWindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.content.ContentFactory
import org.intellij.plugins.markdown.ui.preview.jcef.MarkdownJCEFHtmlPanel
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import org.jetbrains.plugins.template.services.AiUiStateService
import java.awt.BorderLayout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.JButton
import javax.swing.JPanel
import java.awt.FlowLayout
import com.intellij.util.ui.JBUI


class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: com.intellij.openapi.project.Project, toolWindow: ToolWindow) {
        val vf = LightVirtualFile("answer.md", "")
        val preview = MarkdownJCEFHtmlPanel(project, vf)

        val ui = project.service<AiUiStateService>()

        val sendEmailBtn = JButton("Send email")

        val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            border = JBUI.Borders.empty(8)   // padding, optional
            isOpaque = false                 // optional
            add(sendEmailBtn)                // button keeps preferred size (small)
        }

        val panel = JPanel(BorderLayout()).apply {
            add(preview.component, BorderLayout.CENTER)
            add(buttonBar, BorderLayout.SOUTH)  // ✅ below preview
        }

        val content = ContentFactory.getInstance()
            .createContent(panel, "Answer", false)

        toolWindow.contentManager.addContent(content)
        Disposer.register(content, preview)

        val listener: (String) -> Unit = { markdown ->
            val html = MarkdownUtil.generateMarkdownHtml(vf, markdown, project)
            ApplicationManager.getApplication().invokeLater {
                preview.setHtml(html, 0)
            }
        }

        ui.addListener(listener)
        Disposer.register(content) { ui.removeListener(listener) }

        sendEmailBtn.addActionListener {
            // Get email; if missing, ask once and store

            val payload = ui.getLastEmailPayload()
            if (payload == null) {
                Messages.showInfoMessage(project, "Nothing to send yet (run Ask OpenAI first).", "Send Email")
                return@addActionListener
            }
            var to = payload.email
            if (to.isNullOrBlank()) {
                to = Messages.showInputDialog(
                    project,
                    "Enter recipient email:",
                    "Send Email",
                    Messages.getQuestionIcon()
                )?.trim()

                if (to.isNullOrBlank()) return@addActionListener

            }
            val subject = "[ISSUE]: ${payload.filePath ?: "unknown file"}"
            val body = buildString {
                appendLine("Dear ${payload.name}")
                appendLine()
                appendLine("This is an automatically generated message.")
                appendLine()
                appendLine("Question:")
                appendLine(payload.question)
                appendLine()
                appendLine("File path:")
                appendLine(payload.filePath ?: "(unknown)")
                appendLine()
                appendLine("Selected snippet:")
                appendLine("```")
                appendLine(payload.selectedSnippet ?: "(no selection)")
                appendLine("```")
            }

            BrowserUtil.browse(buildMailto(to, subject, body))
        }
    }
    private fun encMailto(s: String): String =
        java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20")

    private fun buildMailto(to: String, subject: String, body: String): String {
        val mailto = "mailto:$to?subject=${encMailto(subject)}&body=${encMailto(body)}"
        return mailto
    }
}
