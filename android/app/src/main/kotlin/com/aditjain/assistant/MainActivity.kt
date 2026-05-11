package com.aditjain.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val activityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* No-op. */ }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show()
    }

    private val multiPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Denied: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Each installDebug invalidates our NotificationListenerService binding.
        // If the user has already granted access, asking the system to rebind
        // restores it without forcing them back to Settings.
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, AssistantNotificationListener::class.java)
            )
        }

        findViewById<Button>(R.id.setDefaultButton).setOnClickListener { openAssistantPicker() }
        findViewById<Button>(R.id.notifAccessButton).setOnClickListener { openNotificationAccess() }
        findViewById<Button>(R.id.permsButton).setOnClickListener { requestCallAndContacts() }
        findViewById<Button>(R.id.testButton).setOnClickListener { testBackend() }
    }

    private fun openAssistantPicker() {
        Toast.makeText(this, "Tap 'Digital assistant app' → pick Assistant", Toast.LENGTH_LONG).show()
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                activityLauncher.launch(intent)
                return
            } catch (_: ActivityNotFoundException) { /* try next */ }
        }
    }

    private fun openNotificationAccess() {
        Toast.makeText(this, "Find 'Assistant' and toggle it ON", Toast.LENGTH_LONG).show()
        activityLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestCallAndContacts() {
        multiPermsLauncher.launch(arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        ))
    }

    private fun testBackend() {
        val transcript = findViewById<EditText>(R.id.testInput).text.toString().trim()
        if (transcript.isEmpty()) {
            Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show()
            return
        }
        val replyView = findViewById<TextView>(R.id.testReply)
        replyView.text = "calling…"
        scope.launch {
            val reply = try {
                withContext(Dispatchers.IO) {
                    Backend.callAgent(transcript, JSONArray()).reply
                }
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            replyView.text = reply
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
