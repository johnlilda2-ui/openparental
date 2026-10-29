package app.monii.managed.commands

import android.content.Context
import android.util.Log
import app.monii.managed.admin.AdminManager
import app.monii.managed.data.PolicyStore
import app.monii.managed.net.CommandDto
import app.monii.managed.repo.MoniiRepository

/**
 * Pull pending commands, execute them, then post terminal results. If result
 * reporting fails, the command remains pending and can be replayed; handlers
 * must therefore remain idempotent.
 */
class CommandDispatcher(
    private val context: Context,
    private val repo: MoniiRepository,
    private val admin: AdminManager,
) {
    suspend fun syncAndExecute(): Int {
        val commands = repo.pullCommands()
        for (command in commands) {
            // Apply first, then report a terminal result. If the process dies or
            // reporting fails before the result reaches the server, the command
            // stays pending and is replayed on the next sync. SET_POLICY is
            // idempotent, so this is safer than acknowledging before execution.
            val outcome = runCatching { execute(command) }
            runCatching {
                if (outcome.isSuccess) {
                    repo.result(command.id, outcome.getOrNull(), null)
                } else {
                    repo.result(
                        command.id,
                        null,
                        outcome.exceptionOrNull()?.message ?: "error",
                    )
                }
            }.onFailure {
                Log.w(TAG, "Could not report command ${command.id}; it will retry on sync", it)
            }
        }
        return commands.size
    }

    private fun execute(command: CommandDto): Map<String, Any?> =
        when (command.type) {
            "LOCK" -> {
                admin.lockNow()
                mapOf("locked" to true)
            }
            "UNLOCK" -> mapOf("noop" to "cannot force-unlock without Device Owner")
            "SET_POLICY" -> {
                // The backend wraps rules as { version, rules }; enforcement reads the rules object.
                val rules = command.payload?.get("rules") as? Map<String, Any?>
                    ?: error("SET_POLICY payload is missing rules")
                PolicyStore.save(context, rules)
                mapOf("applied" to true, "version" to command.payload["version"])
            }
            "PING" -> mapOf("pong" to true)
            "REQUEST_LOCATION" -> mapOf("requested" to true) // Phase 6 wires the actual fix
            else -> mapOf("ignored" to command.type)
        }

    companion object {
        private const val TAG = "CommandDispatcher"
    }
}
