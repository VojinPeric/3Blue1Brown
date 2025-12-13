package org.jetbrains.plugins.template.services

data class AiRequest(
    val question: String,
    val selectedSnippet: String? = null,
    val filePath: String? = null,
    val fileText: String? = null,
    val languageHint: String? = null
)