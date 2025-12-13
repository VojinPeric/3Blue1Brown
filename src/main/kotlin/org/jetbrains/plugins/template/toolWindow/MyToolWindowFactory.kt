package org.jetbrains.plugins.template.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.content.ContentFactory
import org.intellij.plugins.markdown.ui.preview.jcef.MarkdownJCEFHtmlPanel
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import org.jetbrains.plugins.template.services.AiUiStateService

class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: com.intellij.openapi.project.Project, toolWindow: ToolWindow) {
        val vf = LightVirtualFile("answer.md", "")
        val preview = MarkdownJCEFHtmlPanel(project, vf) // JetBrains markdown preview panel :contentReference[oaicite:2]{index=2}

        val content = ContentFactory.getInstance()
            .createContent(preview.component, "Answer", false)

        toolWindow.contentManager.addContent(content)

        // Important: dispose preview when toolwindow content is disposed
        Disposer.register(content, preview)

        val ui = project.service<AiUiStateService>()

        val listener: (String) -> Unit = { markdown ->
            // Convert markdown -> HTML and render
            val html = MarkdownUtil.generateMarkdownHtml(vf, markdown, project)
            ApplicationManager.getApplication().invokeLater {
                preview.setHtml(html, 0)
            }
        }

        ui.addListener(listener)
        Disposer.register(content) { ui.removeListener(listener) }
    }
}
