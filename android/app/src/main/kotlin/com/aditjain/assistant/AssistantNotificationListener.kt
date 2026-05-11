package com.aditjain.assistant

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

private const val TAG = "NotifListener"

class AssistantNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val n = sbn.notification ?: return
        // Skip persistent / foreground-service spam.
        if (sbn.isOngoing) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val app = friendlyAppName(sbn.packageName)

        // Capture a free-form-text reply action if the notification exposes one.
        // This is how messaging apps (WhatsApp, Telegram, Messages, etc.) let
        // Android Auto / Wear send silent replies on the user's behalf.
        val replyAction = n.actions?.firstOrNull { a ->
            a?.remoteInputs?.any { it.allowFreeFormInput } == true
        }

        NotificationStore.add(
            NotificationStore.Item(
                key = sbn.key,
                app = app,
                packageName = sbn.packageName,
                title = title,
                text = text,
                timestamp = sbn.postTime,
                replyAction = replyAction,
            )
        )
        Log.d(TAG, "captured [$app] $title — ${text.take(60)}  reply=${replyAction != null}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { NotificationStore.remove(it) }
    }

    private fun friendlyAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val ai: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
