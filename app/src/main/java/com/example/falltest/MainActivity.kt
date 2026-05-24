package com.example.falltest

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: TextView
    private lateinit var statusSubText: TextView
    private lateinit var alertCard: MaterialCardView
    private lateinit var countdownText: TextView
    private lateinit var cancelButton: MaterialButton
    private lateinit var startStopButton: MaterialButton
    private lateinit var addContactButton: MaterialButton
    private lateinit var contactsContainer: LinearLayout
    private lateinit var noContactsText: TextView
    private lateinit var senderEmailText: TextView
    private lateinit var editEmailSettingsButton: MaterialButton

    private var countDownTimer: CountDownTimer? = null
    private var isMonitoring = false

    companion object {
        const val PERMISSION_REQUEST_CODE = 1001
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "FALL_DETECTED" -> startCountdown()
                "FALL_ALERT_CANCELLED" -> onAlertCancelled()
                "FALL_ALERT_SENT" -> onAlertSent()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "Fall Guard"

        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
        statusSubText = findViewById(R.id.statusSubText)
        alertCard = findViewById(R.id.alertCard)
        countdownText = findViewById(R.id.countdownText)
        cancelButton = findViewById(R.id.cancelButton)
        startStopButton = findViewById(R.id.startStopButton)
        addContactButton = findViewById(R.id.addContactButton)
        contactsContainer = findViewById(R.id.contactsContainer)
        noContactsText = findViewById(R.id.noContactsText)
        senderEmailText = findViewById(R.id.senderEmailText)
        editEmailSettingsButton = findViewById(R.id.editEmailSettingsButton)

        isMonitoring = ContactsManager.isMonitoring(this)
        if (isMonitoring) setMonitoringState() else setStoppedState()

        startStopButton.setOnClickListener {
            if (isMonitoring) stopMonitoring() else checkPermissionsAndStart()
        }
        cancelButton.setOnClickListener { cancelAlert() }
        addContactButton.setOnClickListener { showAddContactDialog() }
        editEmailSettingsButton.setOnClickListener { showEmailSettingsDialog() }

        refreshContactsList()
        refreshSenderEmail()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("FALL_DETECTED")
            addAction("FALL_ALERT_CANCELLED")
            addAction("FALL_ALERT_SENT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(broadcastReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(broadcastReceiver)
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startMonitoring()
        else ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) startMonitoring()
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(this, Intent(this, FallDetectionService::class.java))
        isMonitoring = true
        ContactsManager.setMonitoring(this, true)
        setMonitoringState()
    }

    private fun stopMonitoring() {
        stopService(Intent(this, FallDetectionService::class.java))
        isMonitoring = false
        ContactsManager.setMonitoring(this, false)
        countDownTimer?.cancel()
        alertCard.visibility = View.GONE
        setStoppedState()
    }

    private fun setStoppedState() {
        statusCard.setCardBackgroundColor(Color.parseColor("#607D8B"))
        statusText.text = "Monitoring Stopped"
        statusSubText.text = "Tap Start Monitoring to begin protection"
        startStopButton.text = "Start Monitoring"
        startStopButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1565C0"))
    }

    private fun setMonitoringState() {
        statusCard.setCardBackgroundColor(Color.parseColor("#2E7D32"))
        statusText.text = "Monitoring Active"
        statusSubText.text = "Fall detection is running in the background"
        startStopButton.text = "Stop Monitoring"
        startStopButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
    }

    private fun startCountdown() {
        statusCard.setCardBackgroundColor(Color.parseColor("#C62828"))
        statusText.text = "Fall Detected!"
        statusSubText.text = "Alert will be sent if not cancelled in time"
        alertCard.visibility = View.VISIBLE
        cancelButton.isEnabled = true
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownText.text = "${millisUntilFinished / 1000}s"
            }
            override fun onFinish() {
                countdownText.text = "Sent!"
                cancelButton.isEnabled = false
            }
        }.start()
    }

    private fun cancelAlert() {
        countDownTimer?.cancel()
        sendBroadcast(Intent(FallDetectionService.ACTION_CANCEL_ALERT))
        alertCard.visibility = View.GONE
        if (isMonitoring) setMonitoringState()
    }

    private fun onAlertCancelled() {
        countDownTimer?.cancel()
        alertCard.visibility = View.GONE
        if (isMonitoring) setMonitoringState()
    }

    private fun onAlertSent() {
        countDownTimer?.cancel()
        alertCard.visibility = View.GONE
        if (isMonitoring) setMonitoringState()
        Toast.makeText(this, "Emergency alert emailed to your contacts", Toast.LENGTH_LONG).show()
    }

    private fun showAddContactDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Emergency Contact")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = view.findViewById<EditText>(R.id.nameInput).text.toString().trim()
                val email = view.findViewById<EditText>(R.id.emailInput).text.toString().trim()
                if (name.isNotEmpty() && email.isNotEmpty()) {
                    ContactsManager.addContact(this, Contact(name, email))
                    refreshContactsList()
                } else {
                    Toast.makeText(this, "Please enter both name and email", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmailSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_email_settings, null)
        view.findViewById<EditText>(R.id.senderEmailInput).setText(ContactsManager.getSenderEmail(this))
        view.findViewById<EditText>(R.id.senderPasswordInput).setText(ContactsManager.getSenderPassword(this))

        MaterialAlertDialogBuilder(this)
            .setTitle("Sender Email Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val email = view.findViewById<EditText>(R.id.senderEmailInput).text.toString().trim()
                val password = view.findViewById<EditText>(R.id.senderPasswordInput).text.toString().trim()
                if (email.isNotEmpty() && password.isNotEmpty()) {
                    ContactsManager.setSenderCredentials(this, email, password)
                    refreshSenderEmail()
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Send Test Email") { _, _ ->
                val email = view.findViewById<EditText>(R.id.senderEmailInput).text.toString().trim()
                val password = view.findViewById<EditText>(R.id.senderPasswordInput).text.toString().trim()
                if (email.isNotEmpty() && password.isNotEmpty()) {
                    ContactsManager.setSenderCredentials(this, email, password)
                    refreshSenderEmail()
                    Toast.makeText(this, "Sending test email...", Toast.LENGTH_SHORT).show()
                    EmailSender.sendTestEmail(this) { success, error ->
                        runOnUiThread {
                            if (success) {
                                Toast.makeText(this, "Test email sent! Check your inbox.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Failed: $error", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(this, "Please fill in both fields first", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshSenderEmail() {
        val email = ContactsManager.getSenderEmail(this)
        senderEmailText.text = if (email.isBlank()) "Not configured" else email
    }

    private fun refreshContactsList() {
        val contacts = ContactsManager.getContacts(this)
        contactsContainer.removeAllViews()
        if (contacts.isEmpty()) {
            noContactsText.visibility = View.VISIBLE
        } else {
            noContactsText.visibility = View.GONE
            contacts.forEachIndexed { index, contact ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_contact, contactsContainer, false)
                row.findViewById<TextView>(R.id.contactName).text = contact.name
                row.findViewById<TextView>(R.id.contactEmail).text = contact.email
                row.findViewById<ImageButton>(R.id.deleteContact).setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Remove Contact")
                        .setMessage("Remove ${contact.name} from emergency contacts?")
                        .setPositiveButton("Remove") { _, _ ->
                            ContactsManager.removeContact(this, index)
                            refreshContactsList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                contactsContainer.addView(row)
                if (index < contacts.size - 1) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).also { it.setMargins(0, 4, 0, 4) }
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                    }
                    contactsContainer.addView(divider)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
