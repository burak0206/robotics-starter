package dev.robotics

import com.fasterxml.jackson.databind.JsonNode
import dev.robotics.proto.ElevatorStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ElevatorStatusDto(
    val elevatorId: String,
    val mode: String,
    val state: String,
    val currentFloor: Int,
    val pickupFloor: Int?,
    val destinationFloor: Int?,
    val doorsOpen: Boolean,
    val robotInCabin: Boolean,
    val observedAtUnixMs: Long,
)

fun ElevatorStatus.toDto() = ElevatorStatusDto(
    elevatorId = elevatorId,
    mode = mode,
    state = state,
    currentFloor = currentFloor,
    pickupFloor = if (hasPickupFloor()) pickupFloor else null,
    destinationFloor = if (hasDestinationFloor()) destinationFloor else null,
    doorsOpen = doorsOpen,
    robotInCabin = robotInCabin,
    observedAtUnixMs = observedAtUnixMs,
)

@RestController
@RequestMapping("/api/elevator")
class ElevatorController(private val gateway: RobotGateway) {
    @GetMapping("/status")
    fun status() = gateway.elevatorStatus().toDto()

    @PostMapping("/call")
    fun call(@RequestBody body: JsonNode) = gateway.callElevator(body.floor("pickupFloor")).toDto()

    @PostMapping("/select-floor")
    fun selectFloor(@RequestBody body: JsonNode) =
        gateway.selectElevatorFloor(body.floor("destinationFloor")).toDto()

    @PostMapping("/simulation/advance")
    fun advanceSimulation(@RequestBody body: JsonNode): ElevatorStatusDto {
        val expectedState = body.path("expectedState")
        if (!body.isObject || !expectedState.isTextual || expectedState.textValue().isBlank()) {
            throw ElevatorRequestException()
        }
        return gateway.advanceElevatorSimulation(expectedState.textValue()).toDto()
    }

    private fun JsonNode.floor(field: String): Int {
        val value = path(field)
        if (!isObject || !value.isIntegralNumber || !value.canConvertToInt()) {
            throw ElevatorRequestException()
        }
        return value.intValue()
    }
}

class ElevatorRequestException : RuntimeException()
