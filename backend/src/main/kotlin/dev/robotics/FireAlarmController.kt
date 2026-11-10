package dev.robotics

import dev.robotics.proto.FireAlarmStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class FireAlarmStatusDto(
    val alarmId: String,
    val mode: String,
    val active: Boolean,
    val recoveryRequired: Boolean,
    val doorCancelled: Boolean,
    val elevatorCancelled: Boolean,
    val observedAtUnixMs: Long,
)

fun FireAlarmStatus.toDto() = FireAlarmStatusDto(
    alarmId = alarmId,
    mode = mode,
    active = active,
    recoveryRequired = recoveryRequired,
    doorCancelled = doorCancelled,
    elevatorCancelled = elevatorCancelled,
    observedAtUnixMs = observedAtUnixMs,
)

@RestController
@RequestMapping("/api/fire-alarm")
class FireAlarmController(private val gateway: RobotGateway) {
    @GetMapping("/status")
    fun status() = gateway.fireAlarmStatus().toDto()

    @PostMapping("/simulation/activate")
    fun activate() = gateway.activateFireAlarm().toDto()

    @PostMapping("/simulation/clear")
    fun clear() = gateway.clearFireAlarm().toDto()

    @PostMapping("/simulation/reset-cancelled")
    fun resetCancelled() = gateway.resetCancelledSimulations().toDto()
}
