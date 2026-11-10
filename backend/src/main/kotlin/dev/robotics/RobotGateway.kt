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

    @PreDestroy
    override fun close() {
        channel.shutdownNow()
    }
}

class ElevatorRpcException(val failure: StatusRuntimeException) : RuntimeException(failure)
