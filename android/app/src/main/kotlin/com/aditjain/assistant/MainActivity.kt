package com.aditjain.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* No-op — user picked or canceled. */ }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        findViewById<Button>(R.id.setDefaultButton).setOnClickListener { openAssistantPicker() }
        findViewById<Button>(R.id.testButton).setOnClickListener { testBackend() }
    }

    private fun openAssistantPicker() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            return
        }
        roleLauncher.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
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
                    val body = JSONObject().put("transcript", transcript).toString()
                        .toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("${BuildConfig.BACKEND_URL}/agent")
                        .header("X-API-Key", BuildConfig.API_KEY)
                        .post(body)
                        .build()
                    http.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            JSONObject(raw).optString("reply")
                        } else {
                            "HTTP ${resp.code}: ${raw.take(200)}"
                        }
                    }
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
