package com.aditjain.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Backend {

    data class Result(
        val reply: String,
        val history: JSONArray,
        val actions: JSONArray,
    )

    fun callAgent(
        transcript: String,
        history: JSONArray,
        notifications: List<NotificationStore.Item> = emptyList(),
    ): Result {
        val url = URL("${BuildConfig.BACKEND_URL}/agent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", BuildConfig.API_KEY)
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
        }

        val notificationsArr = JSONArray()
        for (n in notifications) {
            notificationsArr.put(
                JSONObject()
                    .put("app", n.app)
                    .put("title", n.title)
                    .put("text", n.text)
                    .put("ts", n.timestamp)
            )
        }

        val payload = JSONObject()
            .put("transcript", transcript)
            .put("history", history)
            .put("notifications", notificationsArr)
            .toString()
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            return Result("HTTP $code: ${raw.take(200)}", history, JSONArray())
        }
        val obj = JSONObject(raw)
        return Result(
            reply = obj.optString("reply").ifBlank { "(empty)" },
            history = obj.optJSONArray("history") ?: history,
            actions = obj.optJSONArray("actions") ?: JSONArray(),
        )
    }
}
