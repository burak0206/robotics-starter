package dev.robotics

import dev.robotics.proto.GetStatusRequest
import dev.robotics.proto.RobotServiceGrpc
import dev.robotics.proto.RobotStatus
import dev.robotics.proto.StopRequest
import dev.robotics.proto.StopResponse
import dev.robotics.proto.AdvanceElevatorSimulationRequest
import dev.robotics.proto.CallElevatorRequest
import dev.robotics.proto.ElevatorServiceGrpc
import dev.robotics.proto.ElevatorStatus
import dev.robotics.proto.GetElevatorStatusRequest
import dev.robotics.proto.SelectFloorRequest
import dev.robotics.proto.AdvanceDoorSimulationRequest
import dev.robotics.proto.DoorServiceGrpc
import dev.robotics.proto.DoorStatus
import dev.robotics.proto.GetDoorStatusRequest
import dev.robotics.proto.RequestDoorPassageRequest
import dev.robotics.proto.StartDoorPassageRequest
import dev.robotics.proto.ActivateFireAlarmRequest
import dev.robotics.proto.ClearFireAlarmRequest
import dev.robotics.proto.FireAlarmServiceGrpc
import dev.robotics.proto.FireAlarmStatus
import dev.robotics.proto.GetFireAlarmStatusRequest
import dev.robotics.proto.ResetCancelledSimulationsRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class RobotGateway(
    @Value("\${robot.grpc.host}") host: String,
    @Value("\${robot.grpc.port}") port: Int,
) : AutoCloseable {
    private val channel: ManagedChannel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build()
    private val stub = RobotServiceGrpc.newBlockingStub(channel)
    private val elevatorStub = ElevatorServiceGrpc.newBlockingStub(channel)
    private val doorStub = DoorServiceGrpc.newBlockingStub(channel)
    private val fireAlarmStub = FireAlarmServiceGrpc.newBlockingStub(channel)

    fun status(): RobotStatus = stub.withDeadlineAfter(2, TimeUnit.SECONDS)
        .getStatus(GetStatusRequest.getDefaultInstance())

    fun stop(): StopResponse = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
        .stop(StopRequest.getDefaultInstance())

    fun elevatorStatus(): ElevatorStatus = elevatorCall {
        elevatorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getStatus(GetElevatorStatusRequest.getDefaultInstance())
    }

    fun callElevator(pickupFloor: Int): ElevatorStatus = elevatorCall {
        elevatorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .call(CallElevatorRequest.newBuilder().setPickupFloor(pickupFloor).build())
    }

    fun selectElevatorFloor(destinationFloor: Int): ElevatorStatus = elevatorCall {
        elevatorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .selectFloor(SelectFloorRequest.newBuilder().setDestinationFloor(destinationFloor).build())
    }

    fun advanceElevatorSimulation(expectedState: String): ElevatorStatus = elevatorCall {
        elevatorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .advanceSimulation(AdvanceElevatorSimulationRequest.newBuilder().setExpectedState(expectedState).build())
    }

    private fun elevatorCall(operation: () -> ElevatorStatus): ElevatorStatus = try {
        operation()
    } catch (failure: StatusRuntimeException) {
        throw ElevatorRpcException(failure)
    }

    fun doorStatus(): DoorStatus = doorCall {
        doorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getStatus(GetDoorStatusRequest.getDefaultInstance())
    }

    fun requestDoorPassage(): DoorStatus = doorCall {
        doorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .requestPassage(RequestDoorPassageRequest.getDefaultInstance())
    }

    fun startDoorPassage(): DoorStatus = doorCall {
        doorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .startPassage(StartDoorPassageRequest.getDefaultInstance())
    }

    fun advanceDoorSimulation(expectedState: String): DoorStatus = doorCall {
        doorStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .advanceSimulation(AdvanceDoorSimulationRequest.newBuilder().setExpectedState(expectedState).build())
    }

    private fun doorCall(operation: () -> DoorStatus): DoorStatus = try {
        operation()
    } catch (failure: StatusRuntimeException) {
        throw DoorRpcException(failure)
    }

    fun fireAlarmStatus(): FireAlarmStatus = fireAlarmCall {
        fireAlarmStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getStatus(GetFireAlarmStatusRequest.getDefaultInstance())
    }

    fun activateFireAlarm(): FireAlarmStatus = fireAlarmCall {
        fireAlarmStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .activate(ActivateFireAlarmRequest.getDefaultInstance())
    }

    fun clearFireAlarm(): FireAlarmStatus = fireAlarmCall {
        fireAlarmStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .clear(ClearFireAlarmRequest.getDefaultInstance())
    }

    fun resetCancelledSimulations(): FireAlarmStatus = fireAlarmCall {
        fireAlarmStub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .resetCancelledSimulations(ResetCancelledSimulationsRequest.getDefaultInstance())
    }

    private fun fireAlarmCall(operation: () -> FireAlarmStatus): FireAlarmStatus = try {
        operation()
    } catch (failure: StatusRuntimeException) {
        throw FireAlarmRpcException(failure)
    }

    @PreDestroy
    override fun close() {
        channel.shutdownNow()
    }
}

class ElevatorRpcException(val failure: StatusRuntimeException) : RuntimeException(failure)
class DoorRpcException(val failure: StatusRuntimeException) : RuntimeException(failure)
class FireAlarmRpcException(val failure: StatusRuntimeException) : RuntimeException(failure)
