package app.monii.managed.commands

import android.content.Context
import com.google.gson.Gson

/**
 * Durable private queue for command outcomes. A device may complete a command
 * while offline or lose the response after the backend commits it; retrying the
 * same command result is safe because the server treats terminal states as
 * immutable. This queue holds only result payloads until the next sync.
 */
internal class CommandResultQueue(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )
    private val gson = Gson()

    @Synchronized
    fun enqueue(item: PendingCommandResult) {
        val items = read().filterNot { it.commandId == item.commandId }.toMutableList()
        items.add(item)
        write(items)
    }

    @Synchronized
    fun all(): List<PendingCommandResult> = read()

    @Synchronized
    fun remove(commandId: String) {
        write(read().filterNot { it.commandId == commandId })
    }

    private fun read(): List<PendingCommandResult> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson(json, Array<PendingCommandResult>::class.java)?.toList()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<PendingCommandResult>) {
        prefs.edit().putString(KEY, gson.toJson(items)).commit()
    }

    companion object {
        private const val PREFS = "monii_pending_command_results"
        private const val KEY = "results"
    }
}

internal data class PendingCommandResult(
    val commandId: String,
    val result: Map<String, Any?>?,
    val error: String?,
)
