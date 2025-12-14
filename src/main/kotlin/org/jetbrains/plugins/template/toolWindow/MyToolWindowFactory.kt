package org.jetbrains.plugins.template.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.intellij.plugins.markdown.ui.preview.jcef.MarkdownJCEFHtmlPanel
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import org.jetbrains.plugins.template.services.AiUiStateService
import org.jetbrains.plugins.template.services.EmailPayload
import org.jetbrains.plugins.template.services.SmtpEmailService
import org.jetbrains.plugins.template.services.smtpConfigFromEnv
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel


class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: com.intellij.openapi.project.Project, toolWindow: ToolWindow) {
        val vf = LightVirtualFile("answer.md", "")
        val preview = MarkdownJCEFHtmlPanel(project, vf)
        val ui = project.service<AiUiStateService>()

        // Keep the latest payload for compose/send
        var composePayload: EmailPayload? = null

        // ---------- Answer view ----------
        val explanationLabel = JLabel("Not satisfied with Athena's answer? Escalate further:").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(8)
            font = JBUI.Fonts.label(11F)
        }

        val sendIssueButton = JButton("Issue")
        val sendEmailBtn = JButton("Email")

        val answerButtonBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            border = JBUI.Borders.empty(8)
            isOpaque = false
            add(explanationLabel)
            add(sendIssueButton)
            add(sendEmailBtn)                // button keeps preferred size (small)
        }

        val answerPanel = JPanel(BorderLayout()).apply {
            add(preview.component, BorderLayout.CENTER)
            add(answerButtonBar, BorderLayout.SOUTH)
        }

        // ---------- Compose view (user edits ONLY the question) ----------
        val toField = JBTextField()
        val subjectField = JBTextField()
        val questionArea = JBTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
        }
//        val explanationLabel2 = JLabel("Selected code and file path\n will be sent along with your message").apply {
//            foreground = UIUtil.getContextHelpForeground()
//            border = JBUI.Borders.emptyLeft(8)
//            font = JBUI.Fonts.label(11F)
//        }
        val backBtn = JButton("Back")
        val sendBtn = JButton("Send a message to editor")

        val topForm = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8)
            val c = GridBagConstraints().apply {
                gridx = 0; gridy = 0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(0, 0, 6, 8)
            }

            add(JLabel("To:"), c)

            c.gridx = 1
            c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            add(toField, c)

            c.gridx = 0
            c.gridy = 1
            c.weightx = 0.0
            c.fill = GridBagConstraints.NONE
            c.insets = JBUI.insets(0, 0, 0, 8)
            add(JLabel("Subject:"), c)

            c.gridx = 1
            c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            c.insets = JBUI.insets(0, 0, 0, 0)
            add(subjectField, c)
        }

        val questionPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 8, 8, 8)
            add(JLabel("Your message to code editor:"), BorderLayout.NORTH)
            add(JBScrollPane(questionArea), BorderLayout.CENTER)
        }

        val composeButtons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            border = JBUI.Borders.empty(8)
            isOpaque = false
            //add(explanationLabel2)
            add(backBtn)
            add(sendBtn)
        }

        val composePanel = JPanel(BorderLayout()).apply {
            add(topForm, BorderLayout.NORTH)
            add(questionPanel, BorderLayout.CENTER)
            add(composeButtons, BorderLayout.SOUTH)
        }

        // ---------- Cards container ----------
        val cards = JPanel(CardLayout()).apply {
            add(answerPanel, "answer")
            add(composePanel, "compose")
        }

        val content = ContentFactory.getInstance()
            .createContent(cards, "Answer", false)

        toolWindow.contentManager.addContent(content)
        Disposer.register(content, preview)

        val layout = cards.layout as CardLayout

        // Always render AI answer into preview (toolwindow preview is NOT used as email preview)
        val listener: (String) -> Unit = { markdown ->
            val html = MarkdownUtil.generateMarkdownHtml(vf, markdown, project)
            ApplicationManager.getApplication().invokeLater {
                preview.setHtml(html, 0)
            }
        }
        ui.addListener(listener)
        Disposer.register(content) { ui.removeListener(listener) }

        // ---------- Actions ----------
        sendIssueButton.addActionListener {
            val payload = ui.getLastEmailPayload()
            if (payload == null) {
                Messages.showInfoMessage(project, "Nothing to send yet (run Ask OpenAI first).", "Send Issue")
                return@addActionListener
            }
            if (payload.originPath == null) {
                Messages.showInfoMessage(project, "No remote found", "Send Issue")
                return@addActionListener
            }
            val body = """
                ## Auto-Generated Code Assistance Issue
                
                **This issue was automatically created** by the Athena plugin when a developer had a question about code in this repository.
                
                ### Context
                - **File**: `${payload.filePath ?: "unknown file"}` (lines `${payload.startLine}-${payload.endLine}`)
                - **Question**: `${payload.question}`
                - **Selected Snippet**:
                ```
                ${payload.selectedSnippet}
                ```
                
                **Developer Action Required**: Review above and provide feedback.
            """.trimIndent()
            createGithubIssue(project, "[ISSUE]: ${payload.filePath ?: "unknown file"}", body, payload.originPath)
        }

        sendEmailBtn.addActionListener {
            val payload = ui.getLastEmailPayload()
            if (payload == null) {
                Messages.showInfoMessage(project, "Nothing to send yet (run Ask OpenAI first).", "Escalate")
                return@addActionListener
            }

            composePayload = payload

            // Prefill To/Subject
            val toPrefill = payload.email?.takeIf { it.isNotBlank() } ?: ""
            val repoName = java.io.File(project.basePath ?: project.name).name
            val subjectPrefill = "[ISSUE] $repoName"

            toField.text = toPrefill
            subjectField.text = subjectPrefill

            // User controls ONLY the question text
            questionArea.text = payload.question

            // Switch to compose view, but keep preview showing AI answer
            layout.show(cards, "compose")
        }

        backBtn.addActionListener {
            layout.show(cards, "answer")
            // Preview already tracks AI answer via listener, but force refresh just in case
            val answerMd = ui.getLastAnswer()
            val html = MarkdownUtil.generateMarkdownHtml(vf, answerMd, project)
            ApplicationManager.getApplication().invokeLater {
                preview.setHtml(html, 0)
            }
        }

        sendBtn.addActionListener {
            val payload = composePayload
            if (payload == null) {
                Messages.showInfoMessage(project, "Nothing to send yet (run Ask OpenAI first).", "Send Email")
                return@addActionListener
            }

            val toValue = toField.text.trim()
            val subjectValue = subjectField.text.trim()
            val questionText = questionArea.text.trim()

            if (toValue.isBlank()) {
                Messages.showInfoMessage(project, "Recipient email is empty.", "Send Email")
                return@addActionListener
            }
            if (subjectValue.isBlank()) {
                Messages.showInfoMessage(project, "Subject is empty.", "Send Email")
                return@addActionListener
            }

            // Build fixed-format markdown (user only changes questionText)
            val md = buildEmailMarkdown(payload, questionText)

            // Convert markdown -> HTML and wrap in email-safe template
            val inner = MarkdownUtil.generateMarkdownHtml(vf, md, project)
            val emailHtml = wrapEmailHtml(styleCodeBlocks(inner), title = subjectValue)
            val textFallback = md

            // Send off-EDT
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val cfg = smtpConfigFromEnv()
                    project.service<SmtpEmailService>()
                        .sendHtml(cfg, toValue, subjectValue, emailHtml, textFallback)

                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, "Email sent successfully.", "Send Email")
                        layout.show(cards, "answer")
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to send email: ${t.message}", "Send Email")
                    }
                }
            }
        }
    }

    // Fixed email layout: user only edits "Question"
    private fun buildEmailMarkdown(payload: EmailPayload, questionText: String): String {
        val name = payload.name?.takeIf { it.isNotBlank() } ?: "there"
        val q = if (questionText.isBlank()) payload.question else questionText

        return buildString {
            appendLine("Dear $name,")
            appendLine()
            appendLine("This is an automatically generated escalation message by Athena de-escalator.")
            appendLine()

            appendLine("## Question")
            appendLine(q)
            appendLine()

            appendLine("## File")
            appendLine(payload.filePath ?: "(unknown)")
            appendLine()

            appendLine("## Selected snippet")
            appendLine("```")
            appendLine(payload.selectedSnippet ?: "(no selection)")
            appendLine("```")
        }
    }

    // ---- Email HTML helpers ----

    private fun styleCodeBlocks(html: String): String {
        var h = html

        h = h.replace(Regex("<pre(\\s[^>]*)?>")) { m ->
            val attrs = m.groupValues.getOrNull(1).orEmpty()
            """<pre$attrs style="background:#0b1220;color:#e6edf3;padding:12px;border-radius:10px;overflow:auto;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,'Liberation Mono','Courier New',monospace;font-size:12px;line-height:1.45;">"""
        }

        h = h.replace(Regex("<code(\\s[^>]*)?>")) { m ->
            val attrs = m.groupValues.getOrNull(1).orEmpty()
            """<code$attrs style="font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,'Liberation Mono','Courier New',monospace;">"""
        }

        return h
    }

    private fun wrapEmailHtml(contentHtml: String, title: String): String {
        return """
<!doctype html>
<html>
  <body style="margin:0;padding:0;background:#f6f8fa;">
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#f6f8fa;padding:24px 0;">
      <tr>
        <td align="center">
          <table role="presentation" width="640" cellspacing="0" cellpadding="0" style="width:640px;max-width:640px;background:#ffffff;border:1px solid #e5e7eb;border-radius:14px;">
            <tr>
              <td style="padding:18px 20px;border-bottom:1px solid #e5e7eb;">
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:16px;font-weight:600;color:#111827;">
                  ${escapeHtml(title)}
                </div>
              </td>
            </tr>
            <tr>
              <td style="padding:18px 20px;">
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:14px;line-height:1.55;color:#111827;">
                  $contentHtml
                </div>
              </td>
            </tr>
            <tr>
              <td style="padding:14px 20px;border-top:1px solid #e5e7eb;">
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12px;color:#6b7280;">
                  Sent via Athena escalation
                </div>
              </td>
            </tr>
          </table>
        </td>
      </tr>
    </table>
  </body>
</html>
""".trimIndent()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun createGithubIssue(project: com.intellij.openapi.project.Project, title: String, body: String, path: String) {
        val json = """
            {
              "title": "${escapeJson(title)}",
              "body": "${escapeJson(body)}",
              "labels": ["question"]
            }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI("https://api.github.com/repos/$path/issues"))
            .header("Authorization", "Bearer ${System.getenv("GITHUB_TOKEN")}")
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()

        val client = HttpClient.newHttpClient()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            Messages.showErrorDialog(project, "Issue failed (Not Allowed)", "Send Issue")
            return
        }

        Messages.showInfoMessage(project, "Issue created", "Send Issue")
    }

    fun escapeJson(str: String) = str.replace("\"", "\\\"").replace("\n", "\\n")
}
