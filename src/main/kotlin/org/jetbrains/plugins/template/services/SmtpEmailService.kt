package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val from: String = username,
    val startTls: Boolean = true,
    val ssl: Boolean = false
)

@Service(Service.Level.PROJECT)
class SmtpEmailService {

    fun sendHtml(cfg: SmtpConfig, to: String, subject: String, html: String, text: String) {
        val props = Properties().apply {
            put("mail.smtp.host", cfg.host)
            put("mail.smtp.port", cfg.port.toString())
            put("mail.smtp.auth", "true")

            if (cfg.ssl) {
                // implicit SSL (465)
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.starttls.enable", "false")
            } else {
                // STARTTLS (587)
                put("mail.smtp.ssl.enable", "false")
                put("mail.smtp.starttls.enable", cfg.startTls.toString())
            }
        }


        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(cfg.username, cfg.password)
        })

        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false))
            setSubject(subject, Charsets.UTF_8.name())
        }

        // multipart/alternative: plain + html
        val multipart = MimeMultipart("alternative")

        MimeBodyPart().also {
            it.setText(text, Charsets.UTF_8.name())
            multipart.addBodyPart(it)
        }

        MimeBodyPart().also {
            it.setContent(html, "text/html; charset=UTF-8")
            multipart.addBodyPart(it)
        }

        msg.setContent(multipart)
        Transport.send(msg)
    }
}
fun smtpConfigFromEnv(): SmtpConfig {
    fun env(name: String) = System.getenv(name)?.trim().orEmpty()
    val host = env("SMTP_HOST")
    val port = env("SMTP_PORT").toIntOrNull() ?: 587
    val user = env("SMTP_USER")
    val pass = env("SMTP_PASS")
    val from = System.getenv("SMTP_FROM")?.trim()?.takeIf { it.isNotBlank() } ?: user

    val ssl = (System.getenv("SMTP_SSL") ?: (port == 465).toString()).toBoolean()
    val startTls = (System.getenv("SMTP_STARTTLS") ?: (!ssl).toString()).toBoolean()

    require(host.isNotBlank()) { "SMTP_HOST missing" }
    require(user.isNotBlank()) { "SMTP_USER missing" }
    require(pass.isNotBlank()) { "SMTP_PASS missing" }

    return SmtpConfig(host, port, user, pass, from = from, startTls = startTls, ssl = ssl)
}