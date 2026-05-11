package com.aditjain.assistant

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log

private const val TAG = "Contacts"

object Contacts {
    data class Match(val displayName: String, val number: String, val totalMatches: Int)

    /**
     * Look up a phone number for a contact by display name. Case-insensitive
     * substring match; if multiple contacts match, return the first and record
     * the count so the caller can warn the user.
     *
     * Requires `READ_CONTACTS` permission.
     */
    fun findPhone(context: Context, query: String): Match? {
        val q = query.trim()
        if (q.isEmpty()) return null

        val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE)
        val selection = "${Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$q%")

        val cursor = try {
            context.contentResolver.query(Phone.CONTENT_URI, projection, selection, args, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS not granted: ${e.message}")
            return null
        } ?: return null

        cursor.use {
            val nameIdx = it.getColumnIndex(Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(Phone.NUMBER)
            val typeIdx = it.getColumnIndex(Phone.TYPE)
            if (nameIdx < 0 || numIdx < 0) return null

            // Walk all rows; prefer mobile numbers among matches with the same name.
            data class Row(val name: String, val number: String, val type: Int)
            val rows = mutableListOf<Row>()
            while (it.moveToNext()) {
                rows.add(Row(
                    it.getString(nameIdx).orEmpty(),
                    it.getString(numIdx).orEmpty(),
                    if (typeIdx >= 0) it.getInt(typeIdx) else 0,
                ))
            }
            if (rows.isEmpty()) return null

            // Sort: mobile first, then everything else
            val sorted = rows.sortedBy { r ->
                if (r.type == Phone.TYPE_MOBILE) 0 else 1
            }
            val pick = sorted.first()
            val distinctNames = rows.map { r -> r.name }.distinct().size
            return Match(pick.name, pick.number, distinctNames)
        }
    }
}
