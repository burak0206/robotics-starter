package dev.robotics

import dev.robotics.proto.GetStatusRequest
import dev.robotics.proto.RobotServiceGrpc
import dev.robotics.proto.RobotStatus
import dev.robotics.proto.StopRequest
import dev.robotics.proto.StopResponse
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
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

    fun status(): RobotStatus = stub.withDeadlineAfter(2, TimeUnit.SECONDS)
        .getStatus(GetStatusRequest.getDefaultInstance())

    fun stop(): StopResponse = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
        .stop(StopRequest.getDefaultInstance())

    @PreDestroy
    override fun close() {
        channel.shutdownNow()
    }
}
