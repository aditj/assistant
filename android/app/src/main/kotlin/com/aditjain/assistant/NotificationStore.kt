package com.aditjain.assistant

import java.util.concurrent.ConcurrentLinkedDeque

/** Bounded LRU-ish cache of recent notifications. Process-scoped (cleared on app death). */
object NotificationStore {
    data class Item(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val timestamp: Long,
    )

    private const val MAX = 30
    private val items = ConcurrentLinkedDeque<Item>()

    fun add(item: Item) {
        // Replace any existing entry with the same key (notification updated).
        items.removeAll { it.key == item.key }
        items.addFirst(item)
        while (items.size > MAX) items.pollLast()
    }

    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    /** Snapshot newest-first. */
    fun snapshot(limit: Int = MAX): List<Item> = items.take(limit).toList()
}
