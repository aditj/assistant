package com.aditjain.assistant

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

private const val TAG = "AssistantSession"

private const val SILENCE_DISMISS_MS = 6_000L
private const val POST_TTS_GAP_MS = 300L

private enum class Status(val label: String, val dotRes: Int) {
    LISTENING("Listening…", R.drawable.dot_listening),
    THINKING("Thinking…", R.drawable.dot_thinking),
    SPEAKING("Speaking…", R.drawable.dot_speaking),
}

class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var statusView: TextView? = null
    private var statusDot: View? = null
    private var transcriptView: TextView? = null
    private var replyView: TextView? = null

    /** OpenAI-format conversation history persisted across turns within this session. */
    private var conversation: JSONArray = JSONArray()

    /** Phone-side actions queued by the most recent agent reply. Executed
     *  after TTS finishes speaking. */
    private var pendingActions: JSONArray = JSONArray()

    /** True once the recognizer has delivered a result for the current
     *  startListening call. Suppresses spurious onError(NO_MATCH). */
    private var resultsReceived = false

    private val silenceDismiss = Runnable {
        Log.i(TAG, "silence timeout — closing session")
        hide()
    }

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.session_overlay, null)
        statusView = view.findViewById(R.id.status)
        statusDot = view.findViewById(R.id.statusDot)
        transcriptView = view.findViewById(R.id.transcript)
        replyView = view.findViewById(R.id.reply)
        view.findViewById<View>(R.id.root).setOnClickListener { hide() }
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        tearDownIO()
        conversation = JSONArray()
        pendingActions = JSONArray()
        transcriptView?.text = ""
        replyView?.text = ""
        initTts()
        startListening()
    }

    private fun initTts() {
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
            } else {
                Log.w(TAG, "Google TTS init failed ($status); falling back to default engine")
                tts = TextToSpeech(context) { fallbackStatus ->
                    if (fallbackStatus == TextToSpeech.SUCCESS) configureTts()
                    else Log.e(TAG, "default TTS init failed status=$fallbackStatus")
                }
            }
        }, "com.google.android.tts")
    }

    private fun configureTts() {
        ttsReady = true
        val t = tts ?: return
        t.language = Locale.US
        pickBestVoice()
        t.setSpeechRate(1.0f)
        t.setPitch(1.0f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { setStatus(Status.SPEAKING) }
            }
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.postDelayed({ afterSpeak() }, POST_TTS_GAP_MS)
            }
        })
        Log.i(TAG, "TTS engine=${t.defaultEngine} ready")
    }

    private fun setStatus(status: Status) {
        statusView?.text = status.label
        statusDot?.setBackgroundResource(status.dotRes)
    }

    private fun pickBestVoice() {
        val t = tts ?: return
        val voices: Set<Voice> = try { t.voices ?: emptySet() } catch (_: Exception) { emptySet() }
        val best = voices
            .filter { it.locale?.language == "en" }
            .filterNot { it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .maxWithOrNull(
                compareBy<Voice> { it.quality }
                    .thenBy { if (it.isNetworkConnectionRequired) 0 else 1 }
            )
        if (best != null) {
            Log.i(TAG, "TTS voice: ${best.name} quality=${best.quality} network=${best.isNetworkConnectionRequired}")
            t.voice = best
        } else {
            Log.w(TAG, "no en voices found, sticking with default")
        }
    }

    private fun startListening() {
        setStatus(Status.LISTENING)
        resultsReceived = false
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    mainHandler.postDelayed(silenceDismiss, SILENCE_DISMISS_MS)
                }
                override fun onBeginningOfSpeech() {
                    mainHandler.removeCallbacks(silenceDismiss)
                }
                override fun onPartialResults(partialResults: Bundle) {
                    val text = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) transcriptView?.text = text
                }
                override fun onResults(results: Bundle) {
                    mainHandler.removeCallbacks(silenceDismiss)
                    resultsReceived = true
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isBlank()) {
                        hide()
                        return
                    }
                    transcriptView?.text = text
                    callAgent(text)
                }
                override fun onError(error: Int) {
                    if (resultsReceived) {
                        Log.d(TAG, "ignoring stale STT error after results: $error")
                        return
                    }
                    mainHandler.removeCallbacks(silenceDismiss)
                    Log.w(TAG, "STT error=$error")
                    hide()
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(intent)
    }

    private fun callAgent(transcript: String) {
        setStatus(Status.THINKING)
        val notifications = NotificationStore.snapshot()
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    Backend.callAgent(transcript, conversation, notifications)
                }
            } catch (e: Exception) {
                Log.e(TAG, "agent call failed", e)
                Backend.Result(
                    reply = "Couldn't reach backend: ${e.message}",
                    history = conversation,
                    actions = JSONArray(),
                )
            }
            conversation = result.history
            pendingActions = result.actions
            replyView?.text = result.reply
            speak(result.reply)
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            mainHandler.postDelayed({ afterSpeak() }, 200)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt-${System.currentTimeMillis()}")
    }

    /** Called once TTS has finished its current utterance. Either runs queued
     *  phone-side actions or resumes listening for a follow-up. */
    private fun afterSpeak() {
        if (pendingActions.length() > 0) {
            executePendingActions()
        } else {
            startListening()
        }
    }

    private fun executePendingActions() {
        val actions = pendingActions
        pendingActions = JSONArray()
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            when (a.optString("type")) {
                "call" -> handleCallAction(a.optString("name"))
                "whatsapp_send" -> handleWhatsappSend(
                    a.optString("contact"),
                    a.optString("text"),
                )
                else -> Log.w(TAG, "unknown action type=${a.optString("type")}")
            }
        }
    }

    private fun handleWhatsappSend(contact: String, text: String) {
        // Path 1: silent reply via the cached notification's RemoteInput action.
        val target = NotificationStore.findReplyTarget("com.whatsapp", contact)
            ?: NotificationStore.findReplyTarget("com.whatsapp.w4b", contact)
        if (target?.replyAction != null) {
            try {
                fireRemoteInputReply(target.replyAction, text)
                Log.i(TAG, "WhatsApp quick-reply sent to ${target.title}")
                hide()
                return
            } catch (e: Exception) {
                Log.w(TAG, "quick-reply failed, falling back to deep link", e)
            }
        }
        // Path 2: deep link with text pre-filled; user taps send in WhatsApp.
        val match = Contacts.findPhone(context, contact)
        if (match == null) {
            speak("I couldn't find $contact in your contacts.")
            return
        }
        val digits = match.number.filter { it.isDigit() }
        val url = "https://wa.me/$digits?text=${Uri.encode(text)}"
        val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Try regular WhatsApp first, then Business, then any handler.
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
            val intent = Intent(baseIntent).apply { setPackage(pkg) }
            try {
                context.startActivity(intent)
                hide()
                return
            } catch (_: Exception) { /* try next */ }
        }
        Log.e(TAG, "no WhatsApp variant available")
        speak("WhatsApp doesn't seem to be installed.")
    }

    private fun fireRemoteInputReply(action: Notification.Action, text: String) {
        val remoteInputs = action.remoteInputs
            ?: throw IllegalStateException("action has no RemoteInput")
        val intent = Intent()
        val bundle = Bundle().apply {
            for (ri in remoteInputs) putCharSequence(ri.resultKey, text)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        action.actionIntent.send(context, 0, intent)
    }

    private fun handleCallAction(name: String) {
        val match = Contacts.findPhone(context, name)
        if (match == null) {
            Log.w(TAG, "no contact for '$name'")
            speak("I couldn't find $name in your contacts.")
            return
        }
        Log.i(TAG, "dialing ${match.displayName} at ${match.number} (matches=${match.totalMatches})")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${match.number}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            hide()
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE missing", e)
            speak("I don't have permission to make calls — grant it in the app.")
        } catch (e: Exception) {
            Log.e(TAG, "call dispatch failed", e)
            speak("Couldn't dial.")
        }
    }

    private fun tearDownIO() {
        mainHandler.removeCallbacks(silenceDismiss)
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        ttsReady = false
    }

    override fun onHide() {
        super.onHide()
        tearDownIO()
        scope.coroutineContext.cancelChildren()
    }
}
