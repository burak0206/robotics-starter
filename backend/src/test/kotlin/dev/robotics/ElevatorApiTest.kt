package dev.robotics

import dev.robotics.proto.AdvanceElevatorSimulationRequest
import dev.robotics.proto.CallElevatorRequest
import dev.robotics.proto.ElevatorServiceGrpc
import dev.robotics.proto.ElevatorStatus
import dev.robotics.proto.GetElevatorStatusRequest
import dev.robotics.proto.SelectFloorRequest
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ElevatorApiTest {
    private val initialStatus = ElevatorStatus.newBuilder()
        .setElevatorId("elevator-01")
        .setMode("simulation")
        .setState("IDLE")
        .setCurrentFloor(0)
        .setDoorsOpen(false)
        .setRobotInCabin(false)
        .setObservedAtUnixMs(1_700_000_000_000)
        .build()

    @Test
    fun `status preserves floor zero and absent requested floors`() {
        withService(object : ElevatorServiceGrpc.ElevatorServiceImplBase() {
            override fun getStatus(request: GetElevatorStatusRequest, observer: StreamObserver<ElevatorStatus>) {
                observer.respond(initialStatus)
            }
        }) { mvc ->
            mvc.perform(get("/api/elevator/status"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.elevatorId").value("elevator-01"))
                .andExpect(jsonPath("$.mode").value("simulation"))
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.currentFloor").value(0))
                .andExpect(jsonPath("$.pickupFloor").value(nullValue()))
                .andExpect(jsonPath("$.destinationFloor").value(nullValue()))
                .andExpect(jsonPath("$.doorsOpen").value(false))
                .andExpect(jsonPath("$.robotInCabin").value(false))
                .andExpect(jsonPath("$.observedAtUnixMs").value(1_700_000_000_000L))
        }
    }

    @Test
    fun `call sends pickup floor zero as a real value`() {
        val requestedFloor = AtomicInteger(-1)
        withService(object : ElevatorServiceGrpc.ElevatorServiceImplBase() {
            override fun call(request: CallElevatorRequest, observer: StreamObserver<ElevatorStatus>) {
                requestedFloor.set(request.pickupFloor)
                observer.respond(initialStatus.toBuilder().setState("CALLING").setPickupFloor(request.pickupFloor).build())
            }
        }) { mvc ->
            mvc.perform(post("/api/elevator/call").contentType(MediaType.APPLICATION_JSON).content("{\"pickupFloor\":0}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("CALLING"))
                .andExpect(jsonPath("$.pickupFloor").value(0))
            assertThat(requestedFloor.get()).isZero()
        }
    }

    @Test
    fun `select floor and simulation event reach their distinct RPCs`() {
        val destination = AtomicInteger(-1)
        val advances = AtomicInteger()
        val expectedState = java.util.concurrent.atomic.AtomicReference<String>()
        withService(object : ElevatorServiceGrpc.ElevatorServiceImplBase() {
            override fun selectFloor(request: SelectFloorRequest, observer: StreamObserver<ElevatorStatus>) {
                destination.set(request.destinationFloor)
                observer.respond(initialStatus.toBuilder().setState("MOVING").setPickupFloor(0)
                    .setDestinationFloor(request.destinationFloor).setRobotInCabin(true).build())
            }

            override fun advanceSimulation(request: AdvanceElevatorSimulationRequest, observer: StreamObserver<ElevatorStatus>) {
                advances.incrementAndGet()
                expectedState.set(request.expectedState)
                observer.respond(initialStatus.toBuilder().setState("WAITING_FOR_EXIT").setPickupFloor(0)
                    .setDestinationFloor(3).setCurrentFloor(3).setDoorsOpen(true).setRobotInCabin(true).build())
            }
        }) { mvc ->
            mvc.perform(post("/api/elevator/select-floor").contentType(MediaType.APPLICATION_JSON).content("{\"destinationFloor\":3}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.destinationFloor").value(3))
                .andExpect(jsonPath("$.doorsOpen").value(false))
                .andExpect(jsonPath("$.robotInCabin").value(true))
            mvc.perform(post("/api/elevator/simulation/advance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedState\":\"MOVING\"}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("WAITING_FOR_EXIT"))
                .andExpect(jsonPath("$.currentFloor").value(3))
                .andExpect(jsonPath("$.doorsOpen").value(true))
            assertThat(destination.get()).isEqualTo(3)
            assertThat(advances.get()).isEqualTo(1)
            assertThat(expectedState.get()).isEqualTo("MOVING")
        }
    }

    @Test
    fun `missing null fractional string oversized and malformed floors are rejected before RPC`() {
        val calls = AtomicInteger()
        withService(object : ElevatorServiceGrpc.ElevatorServiceImplBase() {
            override fun call(request: CallElevatorRequest, observer: StreamObserver<ElevatorStatus>) {
                calls.incrementAndGet()
                observer.respond(initialStatus)
            }

            override fun selectFloor(request: SelectFloorRequest, observer: StreamObserver<ElevatorStatus>) {
                calls.incrementAndGet()
                observer.respond(initialStatus)
            }

            override fun advanceSimulation(request: AdvanceElevatorSimulationRequest, observer: StreamObserver<ElevatorStatus>) {
                calls.incrementAndGet()
                observer.respond(initialStatus)
            }
        }) { mvc ->
            for ((path, field) in listOf("call" to "pickupFloor", "select-floor" to "destinationFloor")) {
                val invalidBodies = listOf("{}", "{\"$field\":null}", "{\"$field\":1.2}",
                    "{\"$field\":\"2\"}", "{\"$field\":2147483648}", "[]", "{", "")
                for (body in invalidBodies) {
                    mvc.perform(post("/api/elevator/$path").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.code").value("ELEVATOR_INVALID_REQUEST"))
                }
            }
            for (body in listOf("{}", "{\"expectedState\":null}", "{\"expectedState\":1}",
                "{\"expectedState\":\"\"}", "{\"expectedState\":\"  \"}", "[]", "{", "")) {
                mvc.perform(post("/api/elevator/simulation/advance").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("ELEVATOR_INVALID_REQUEST"))
            }
            assertThat(calls.get()).isZero()
        }
    }

    @Test
    fun `Rust floor validation maps to 400 and sequence conflicts to 409`() {
        withService(object : ElevatorServiceGrpc.ElevatorServiceImplBase() {
            override fun call(request: CallElevatorRequest, observer: StreamObserver<ElevatorStatus>) {
                observer.onError(Status.INVALID_ARGUMENT.withDescription("private diagnostic").asRuntimeException())
            }

            override fun selectFloor(request: SelectFloorRequest, observer: StreamObserver<ElevatorStatus>) {
                observer.onError(Status.FAILED_PRECONDITION.withDescription("private diagnostic").asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(post("/api/elevator/call").contentType(MediaType.APPLICATION_JSON).content("{\"pickupFloor\":-1}"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("ELEVATOR_INVALID_ARGUMENT"))
            mvc.perform(post("/api/elevator/select-floor").contentType(MediaType.APPLICATION_JSON).content("{\"destinationFloor\":3}"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ELEVATOR_INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Asansör bu işleme hazır değil. Güncel durumu kontrol edip beklenen adımı tamamlayın."))
        }
    }

    @Test
    fun `elevator unavailable and timeout use elevator error codes`() {
        withService(object : ElevatorServiceGrpc.ElevatorServiceImplBase() {
            override fun getStatus(request: GetElevatorStatusRequest, observer: StreamObserver<ElevatorStatus>) {
                observer.onError(Status.UNAVAILABLE.asRuntimeException())
            }

            override fun advanceSimulation(request: AdvanceElevatorSimulationRequest, observer: StreamObserver<ElevatorStatus>) {
                observer.onError(Status.DEADLINE_EXCEEDED.asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(get("/api/elevator/status"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("ELEVATOR_UNAVAILABLE"))
            mvc.perform(post("/api/elevator/simulation/advance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedState\":\"CALLING\"}"))
                .andExpect(status().isGatewayTimeout)
                .andExpect(jsonPath("$.code").value("ELEVATOR_TIMEOUT"))
        }
    }

    private fun StreamObserver<ElevatorStatus>.respond(value: ElevatorStatus) {
        onNext(value)
        onCompleted()
    }

    private fun withService(service: ElevatorServiceGrpc.ElevatorServiceImplBase, block: (MockMvc) -> Unit) {
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
            .addService(service).build().start()
        try {
            RobotGateway("127.0.0.1", server.port).use { gateway ->
                val mvc = MockMvcBuilders.standaloneSetup(ElevatorController(gateway))
                    .setControllerAdvice(ElevatorErrorHandler(), RobotErrorHandler()).build()
                block(mvc)
            }
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
