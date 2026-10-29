package app.monii.managed.data

import android.content.Context
import app.monii.managed.net.EventInputDto
import com.google.gson.Gson
import java.io.File

/**
 * File-backed offline event queue (JSON lines). All instances share one lock so
 * the foreground service and WorkManager cannot drain/rewrite the queue at the
 * same time and silently lose each other's events.
 */
class EventBuffer(context: Context) {
    private val file = File(context.filesDir, "event_buffer.jsonl")
    private val gson = Gson()

    fun add(event: EventInputDto) = synchronized(FILE_LOCK) {
        file.appendText(gson.toJson(event) + "\n")
    }

    fun drainAndClear(): List<EventInputDto> = synchronized(FILE_LOCK) {
        if (!file.exists()) return@synchronized emptyList()
        val items = file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { gson.fromJson(it, EventInputDto::class.java) }.getOrNull() }
        file.writeText("")
        items
    }

    /** Re-queue items whose upload failed, ahead of anything buffered since. */
    fun readd(items: List<EventInputDto>) = synchronized(FILE_LOCK) {
        val sb = StringBuilder()
        items.forEach { sb.append(gson.toJson(it)).append("\n") }
        val existing = if (file.exists()) file.readText() else ""
        file.writeText(sb.toString() + existing)
    }

    companion object {
        private val FILE_LOCK = Any()
    }
}
