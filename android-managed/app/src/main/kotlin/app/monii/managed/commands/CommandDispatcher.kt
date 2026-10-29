package app.monii.managed.commands

import android.content.Context
import app.monii.managed.admin.AdminManager
import app.monii.managed.data.PolicyStore
import app.monii.managed.location.LocationReporter
import app.monii.managed.net.CommandDto
import app.monii.managed.repo.MoniiRepository

/**
 * Pull pending commands, acknowledge each, then execute only if the server
 * confirms ACKED. This prevents duplicate/expired commands from being executed
 * just because a push or stale polling response arrived late.
 */
class CommandDispatcher(
    private val context: Context,
    private val repo: MoniiRepository,
    private val admin: AdminManager,
) {
    suspend fun syncAndExecute(): Int {
        val commands = repo.pullCommands()
        var processed = 0
        for (command in commands) {
            val ack = runCatching { repo.ack(command.id) }.getOrNull() ?: continue
            if (ack.state != "ACKED") continue

            val outcome = runCatching { execute(command) }
            if (outcome.isSuccess) {
                repo.result(command.id, outcome.getOrNull(), null)
            } else {
                repo.result(
                    command.id,
                    null,
                    outcome.exceptionOrNull()?.message ?: "Command failed",
                )
            }
            processed++
        }
        return processed
    }

    private suspend fun execute(command: CommandDto): Map<String, Any?> =
        when (command.type) {
            "LOCK" -> {
                admin.lockNow()
                mapOf("locked" to true)
            }
            "UNLOCK" -> throw UnsupportedOperationException(
                "UNLOCK is not supported for this enrollment; Android does not allow this app to force-unlock the device",
            )
            "SET_POLICY" -> {
                PolicyStore.save(context, command.payload)
                mapOf("applied" to true)
            }
            "PING" -> mapOf("pong" to true)
            "REQUEST_LOCATION" -> LocationReporter(context).reportCurrent(repo)
            else -> throw IllegalArgumentException("Unsupported command type: ${command.type}")
        }
}
