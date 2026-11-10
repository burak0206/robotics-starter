package dev.robotics

import dev.robotics.proto.GetStatusRequest
import dev.robotics.proto.RobotServiceGrpc
import dev.robotics.proto.RobotStatus
import dev.robotics.proto.StopRequest
import dev.robotics.proto.StopResponse
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat

class RobotApiTest {
    private val telemetry = RobotStatus.newBuilder()
        .setRobotId("robot-01")
        .setName("Rover 01")
        .setMode("simulation")
        .setState("IDLE")
        .setBatteryPercent(84)
        .setLinearVelocityMps(0.0)
        .setObservedAtUnixMs(1_700_000_000_000)
        .build()

    @Test
    fun `REST status maps actual gRPC telemetry including timestamp`() {
        withService(object : RobotServiceGrpc.RobotServiceImplBase() {
            override fun getStatus(request: GetStatusRequest, observer: StreamObserver<RobotStatus>) {
                observer.onNext(telemetry)
                observer.onCompleted()
            }
        }) { mvc ->
            mvc.perform(get("/api/robot/status"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.robotId").value("robot-01"))
                .andExpect(jsonPath("$.batteryPercent").value(84))
                .andExpect(jsonPath("$.linearVelocityMps").value(0.0))
                .andExpect(jsonPath("$.observedAtUnixMs").value(1_700_000_000_000L))
        }
    }

    @Test
    fun `unknown ROS telemetry is null rather than fabricated zero`() {
        withService(object : RobotServiceGrpc.RobotServiceImplBase() {
            override fun getStatus(request: GetStatusRequest, observer: StreamObserver<RobotStatus>) {
                observer.onNext(telemetry.toBuilder().setMode("ros2").setState("UNKNOWN")
                    .clearBatteryPercent().clearLinearVelocityMps().build())
                observer.onCompleted()
            }
        }) { mvc ->
            mvc.perform(get("/api/robot/status"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.mode").value("ros2"))
                .andExpect(jsonPath("$.batteryPercent").value(nullValue()))
                .andExpect(jsonPath("$.linearVelocityMps").value(nullValue()))
        }
    }

    @Test
    fun `POST stop reaches robot once and returns accepted command status`() {
        val calls = AtomicInteger()
        withService(object : RobotServiceGrpc.RobotServiceImplBase() {
            override fun stop(request: StopRequest, observer: StreamObserver<StopResponse>) {
                calls.incrementAndGet()
                observer.onNext(StopResponse.newBuilder()
                    .setAccepted(true).setMessage("Stopped")
                    .setStatus(telemetry.toBuilder().setState("STOPPED")).build())
                observer.onCompleted()
            }
        }) { mvc ->
            mvc.perform(post("/api/robot/stop"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status.state").value("STOPPED"))
            assertThat(calls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `unavailable robot produces structured 503 without leaking internals`() {
        withService(object : RobotServiceGrpc.RobotServiceImplBase() {
            override fun getStatus(request: GetStatusRequest, observer: StreamObserver<RobotStatus>) {
                observer.onError(Status.UNAVAILABLE.withDescription("private connection details").asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(get("/api/robot/status"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("ROBOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Robot servisine ulaşılamıyor."))
        }
    }

    @Test
    fun `unresponsive robot hits real client deadline and returns 504`() {
        withService(object : RobotServiceGrpc.RobotServiceImplBase() {
            override fun getStatus(request: GetStatusRequest, observer: StreamObserver<RobotStatus>) {
                // Deliberately do not respond; the client must enforce its deadline.
            }
        }) { mvc ->
            mvc.perform(get("/api/robot/status"))
                .andExpect(status().isGatewayTimeout)
                .andExpect(jsonPath("$.code").value("ROBOT_TIMEOUT"))
        }
    }

    private fun withService(service: RobotServiceGrpc.RobotServiceImplBase, block: (MockMvc) -> Unit) {
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
            .addService(service).build().start()
        try {
            RobotGateway("127.0.0.1", server.port).use { gateway ->
                val mvc = MockMvcBuilders.standaloneSetup(RobotController(gateway))
                    .setControllerAdvice(RobotErrorHandler()).build()
                block(mvc)
            }
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
