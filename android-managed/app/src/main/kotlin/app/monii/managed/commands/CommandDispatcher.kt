package app.monii.managed.commands

import android.content.Context
import android.util.Log
import app.monii.managed.admin.AdminManager
import app.monii.managed.data.PolicyStore
import app.monii.managed.location.LocationReporter
import app.monii.managed.net.CommandDto
import app.monii.managed.repo.MoniiRepository

/** Pull commands, execute, then report their terminal result. */
class CommandDispatcher(
    private val context: Context,
    private val repo: MoniiRepository,
    private val admin: AdminManager,
) {
    suspend fun syncAndExecute(): Int {
        val commands = repo.pullCommands()
        for (command in commands) {
            val outcome = runCatching { execute(command) }
            runCatching {
                if (outcome.isSuccess) repo.result(command.id, outcome.getOrNull(), null)
                else repo.result(command.id, null, outcome.exceptionOrNull()?.message ?: "error")
            }.onFailure {
                Log.w(TAG, "Could not report command ${command.id}; it will retry on sync", it)
            }
        }
        return commands.size
    }

    private suspend fun execute(command: CommandDto): Map<String, Any?> =
        when (command.type) {
            "LOCK" -> {
                admin.lockNow()
                mapOf("locked" to true)
            }
            "UNLOCK" -> mapOf("noop" to "cannot force-unlock without Device Owner")
            "SET_POLICY" -> {
                val rules = command.payload?.get("rules") as? Map<String, Any?>
                    ?: error("SET_POLICY payload is missing rules")
                PolicyStore.save(context, rules)
                Log.i(
                    TAG,
                    "SET_POLICY stored version=${command.payload["version"]}; blockedApps=${(rules["blockedApps"] as? List<*>)?.size ?: 0}",
                )
                mapOf(
                    "applied" to true,
                    "version" to command.payload["version"],
                    "blockedApps" to (rules["blockedApps"] as? List<*>)?.size ?: 0,
                )
            }
            "PING" -> mapOf("pong" to true, "deviceTime" to System.currentTimeMillis())
            "REQUEST_LOCATION" -> {
                val uploaded = LocationReporter(context).reportLastKnown(repo, requireFreshFix = true)
                if (uploaded) mapOf("requested" to true, "uploaded" to true)
                else error("No location fix available; check location services and permission")
            }
            else -> mapOf("ignored" to command.type)
        }

    companion object { private const val TAG = "CommandDispatcher" }
}
