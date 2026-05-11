package com.aditjain.assistant

import android.app.Notification
import java.util.concurrent.ConcurrentLinkedDeque

/** Bounded, in-memory cache of recent notifications. Process-scoped. */
object NotificationStore {
    data class Item(
        val key: String,
        val app: String,
        val packageName: String,
        val title: String,
        val text: String,
        val timestamp: Long,
        /** Notification's free-form-text quick-reply action, if any. Kept as
         *  a live reference (Notification.Action carries a PendingIntent that
         *  needs to be fired against the notifying app's process). */
        val replyAction: Notification.Action? = null,
    )

    private const val MAX = 30
    private val items = ConcurrentLinkedDeque<Item>()

    fun add(item: Item) {
        items.removeAll { it.key == item.key }
        items.addFirst(item)
        while (items.size > MAX) items.pollLast()
    }

    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    fun snapshot(limit: Int = MAX): List<Item> = items.take(limit).toList()

    /**
     * Find the most recent notification from [packageName] whose title (or
     * text) contains [contactQuery] (case-insensitive). Used to determine
     * whether we can fire a silent quick-reply.
     */
    fun findReplyTarget(packageName: String, contactQuery: String): Item? {
        val q = contactQuery.trim().lowercase()
        if (q.isEmpty()) return null
        return items.firstOrNull {
            it.packageName == packageName &&
                it.replyAction != null &&
                (it.title.lowercase().contains(q) || it.text.lowercase().contains(q))
        }
    }
}
