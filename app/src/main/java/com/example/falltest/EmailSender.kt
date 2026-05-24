package com.example.falltest

import android.content.Context
import android.util.Log
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailSender {
    private const val TAG = "EmailSender"

    fun sendFallAlert(context: Context, onResult: (success: Boolean, error: String?) -> Unit = { _, _ -> }) {
        val senderEmail = ContactsManager.getSenderEmail(context)
        val senderPassword = ContactsManager.getSenderPassword(context)
        val contacts = ContactsManager.getContacts(context)

        if (senderEmail.isBlank() || senderPassword.isBlank()) {
            Log.w(TAG, "Sender email/password not configured")
            onResult(false, "Sender email not configured")
            return
        }
        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts to notify")
            onResult(false, "No contacts added")
            return
        }

        Thread {
            try {
                val props = Properties().apply {
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.port", "587")
                    put("mail.smtp.ssl.trust", "smtp.gmail.com")
                    put("mail.smtp.connectiontimeout", "10000")
                    put("mail.smtp.timeout", "10000")
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(senderEmail, senderPassword)
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(senderEmail))
                    for (contact in contacts) {
                        addRecipient(Message.RecipientType.TO, InternetAddress(contact.email))
                    }
                    subject = "EMERGENCY: Fall Detected - Immediate Attention Required"
                    setText(
                        "EMERGENCY FALL ALERT\n\n" +
                        "A fall has been detected on this device.\n\n" +
                        "Please check on this person immediately!\n\n" +
                        "This alert was sent automatically by Fall Guard.\n" +
                        "If this was a false alarm, no further action is needed."
                    )
                }

                Transport.send(message)
                Log.d(TAG, "Fall alert email sent to ${contacts.size} contact(s)")
                onResult(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send email: ${e.message}", e)
                onResult(false, e.message)
            }
        }.start()
    }

    // Used to verify credentials from settings
    fun sendTestEmail(context: Context, onResult: (success: Boolean, error: String?) -> Unit) {
        val senderEmail = ContactsManager.getSenderEmail(context)
        val senderPassword = ContactsManager.getSenderPassword(context)

        if (senderEmail.isBlank() || senderPassword.isBlank()) {
            onResult(false, "Please enter email and password first")
            return
        }

        Thread {
            try {
                val props = Properties().apply {
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.port", "587")
                    put("mail.smtp.ssl.trust", "smtp.gmail.com")
                    put("mail.smtp.connectiontimeout", "10000")
                    put("mail.smtp.timeout", "10000")
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(senderEmail, senderPassword)
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(senderEmail))
                    addRecipient(Message.RecipientType.TO, InternetAddress(senderEmail))
                    subject = "Fall Guard - Test Email"
                    setText("Your Fall Guard app is configured correctly. Alerts will be sent from this account.")
                }

                Transport.send(message)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }.start()
    }
}
