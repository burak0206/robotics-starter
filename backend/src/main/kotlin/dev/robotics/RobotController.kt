package dev.robotics

import dev.robotics.proto.RobotStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RobotStatusDto(
    val robotId: String,
    val name: String,
    val mode: String,
    val state: String,
    val batteryPercent: Int?,
    val linearVelocityMps: Double?,
    val observedAtUnixMs: Long,
)

data class StopResponseDto(val accepted: Boolean, val message: String, val status: RobotStatusDto)

fun RobotStatus.toDto() = RobotStatusDto(
    robotId = robotId,
    name = name,
    mode = mode,
    state = state,
    batteryPercent = if (hasBatteryPercent()) batteryPercent else null,
    linearVelocityMps = if (hasLinearVelocityMps()) linearVelocityMps else null,
    observedAtUnixMs = observedAtUnixMs,
)

@RestController
@RequestMapping("/api/robot")
class RobotController(private val robot: RobotGateway) {
    @GetMapping("/status")
    fun status() = robot.status().toDto()

    @PostMapping("/stop")
    fun stop(): StopResponseDto {
        val response = robot.stop()
        return StopResponseDto(response.accepted, response.message, response.status.toDto())
    }
}
