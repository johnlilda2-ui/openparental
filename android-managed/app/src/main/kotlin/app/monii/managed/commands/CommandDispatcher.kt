package app.monii.managed.commands

import android.content.Context
import app.monii.managed.admin.AdminManager
import app.monii.managed.data.PolicyStore
import app.monii.managed.enforcement.PolicyRules
import app.monii.managed.location.LocationReporter
import app.monii.managed.net.CommandDto
import app.monii.managed.permissions.SpecialAccess
import app.monii.managed.repo.MoniiRepository
import app.monii.managed.supervisor.SupervisorService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val resultQueue = CommandResultQueue(context)

    suspend fun syncAndExecute(): Int = dispatchMutex.withLock {
        flushPendingResults()
        val commands = repo.pullCommands()
        var processed = 0
        for (command in commands) {
            // WorkManager can run after Android has stopped the foreground
            // service. Do not ACK a location command that cannot legally enter
            // visible location foreground mode; leave it pending for the live
            // supervisor loop, or let its TTL expire and report that honestly.
            if (command.type == "REQUEST_LOCATION" && !SupervisorService.isActive()) {
                continue
            }
            val ack = runCatching { repo.ack(command.id) }.getOrNull() ?: continue
            if (ack.state != "ACKED") continue

            val outcome = runCatching { execute(command) }
            resultQueue.enqueue(
                if (outcome.isSuccess) {
                    PendingCommandResult(command.id, outcome.getOrNull(), null)
                } else {
                    PendingCommandResult(
                        command.id,
                        null,
                        outcome.exceptionOrNull()?.message ?: "Command failed",
                    )
                },
            )
            // Keep the result durable until the server accepts it. If the
            // response is lost after server commit, retry is idempotent.
            flushPendingResults()
            processed++
        }
        processed
    }

    private suspend fun flushPendingResults() {
        for (item in resultQueue.all()) {
            val posted = runCatching {
                repo.result(item.commandId, item.result, item.error)
            }.isSuccess
            if (!posted) break
            resultQueue.remove(item.commandId)
        }
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
                val payload = command.payload
                    ?: throw IllegalArgumentException("SET_POLICY is missing its payload")
                val rawRules = payload["rules"] as? Map<*, *>
                    ?: throw IllegalArgumentException("SET_POLICY payload must contain a rules object")
                val rules = rawRules.entries.associate { (key, value) ->
                    val name = key as? String
                        ?: throw IllegalArgumentException("Policy rule names must be strings")
                    name to value
                }
                val parsed = PolicyRules.validate(rules)
                val hasEnforceableRules =
                    parsed.blockedApps.isNotEmpty() ||
                    parsed.appLimits.isNotEmpty() ||
                    parsed.schedules.any { it.blockAll }
                if (hasEnforceableRules && !SpecialAccess.isAccessibilityEnabled(context)) {
                    throw IllegalStateException(
                        "Policy not applied: enable OpenParental Accessibility access on the child device first",
                    )
                }
                if (parsed.appLimits.isNotEmpty() && !SpecialAccess.isUsageAccessGranted(context)) {
                    throw IllegalStateException(
                        "Policy not applied: enable Usage access on the child device to enforce daily app limits",
                    )
                }
                PolicyStore.save(context, rules)
                mapOf(
                    "saved" to true,
                    "enforcementReady" to true,
                    "version" to payload["version"],
                )
            }
            "PING" -> mapOf("pong" to true)
            "REQUEST_LOCATION" -> LocationReporter(context).reportCurrent(repo)
            else -> throw IllegalArgumentException("Unsupported command type: ${command.type}")
        }

    companion object {
        // The foreground service and WorkManager can dispatch concurrently.
        // Serialize pull/ack/execute/result across all dispatcher instances.
        private val dispatchMutex = Mutex()
    }
}
