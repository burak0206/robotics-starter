package dev.robotics

import com.fasterxml.jackson.databind.JsonNode
import dev.robotics.proto.DoorStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DoorStatusDto(
    val doorId: String,
    val mode: String,
    val state: String,
    val doorOpen: Boolean,
    val passageAllowed: Boolean,
    val robotInDoorway: Boolean,
    val observedAtUnixMs: Long,
)

fun DoorStatus.toDto() = DoorStatusDto(
    doorId = doorId,
    mode = mode,
    state = state,
    doorOpen = doorOpen,
    passageAllowed = passageAllowed,
    robotInDoorway = robotInDoorway,
    observedAtUnixMs = observedAtUnixMs,
)

@RestController
@RequestMapping("/api/door")
class DoorController(private val gateway: RobotGateway) {
    @GetMapping("/status")
    fun status() = gateway.doorStatus().toDto()

    @PostMapping("/request-passage")
    fun requestPassage() = gateway.requestDoorPassage().toDto()

    @PostMapping("/start-passage")
    fun startPassage() = gateway.startDoorPassage().toDto()

    @PostMapping("/simulation/advance")
    fun advanceSimulation(@RequestBody body: JsonNode): DoorStatusDto {
        val expectedState = body.path("expectedState")
        if (!body.isObject || !expectedState.isTextual || expectedState.textValue().isBlank()) {
            throw DoorRequestException()
        }
        return gateway.advanceDoorSimulation(expectedState.textValue()).toDto()
    }
}

class DoorRequestException : RuntimeException()
