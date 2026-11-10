package dev.robotics

import dev.robotics.proto.AdvanceDoorSimulationRequest
import dev.robotics.proto.DoorServiceGrpc
import dev.robotics.proto.DoorStatus
import dev.robotics.proto.GetDoorStatusRequest
import dev.robotics.proto.RequestDoorPassageRequest
import dev.robotics.proto.StartDoorPassageRequest
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
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
import java.util.concurrent.atomic.AtomicReference

class DoorApiTest {
    private val readyStatus = DoorStatus.newBuilder()
        .setDoorId("door-01")
        .setMode("simulation")
        .setState("READY_TO_PASS")
        .setDoorOpen(true)
        .setPassageAllowed(true)
        .setRobotInDoorway(false)
        .setObservedAtUnixMs(1_700_000_000_000)
        .build()

    @Test
    fun `status maps door feedback and permission separately`() {
        withService(object : DoorServiceGrpc.DoorServiceImplBase() {
            override fun getStatus(request: GetDoorStatusRequest, observer: StreamObserver<DoorStatus>) {
                observer.respond(readyStatus)
            }
        }) { mvc ->
            mvc.perform(get("/api/door/status"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.doorId").value("door-01"))
                .andExpect(jsonPath("$.mode").value("simulation"))
                .andExpect(jsonPath("$.state").value("READY_TO_PASS"))
                .andExpect(jsonPath("$.doorOpen").value(true))
                .andExpect(jsonPath("$.passageAllowed").value(true))
                .andExpect(jsonPath("$.robotInDoorway").value(false))
                .andExpect(jsonPath("$.observedAtUnixMs").value(1_700_000_000_000L))
        }
    }

    @Test
    fun `passage commands and expected simulation state reach distinct RPCs`() {
        val requests = AtomicInteger()
        val starts = AtomicInteger()
        val advancedState = AtomicReference<String>()
        withService(object : DoorServiceGrpc.DoorServiceImplBase() {
            override fun requestPassage(request: RequestDoorPassageRequest, observer: StreamObserver<DoorStatus>) {
                requests.incrementAndGet()
                observer.respond(readyStatus.toBuilder().setState("OPENING").setDoorOpen(false).setPassageAllowed(false).build())
            }

            override fun startPassage(request: StartDoorPassageRequest, observer: StreamObserver<DoorStatus>) {
                starts.incrementAndGet()
                observer.respond(readyStatus.toBuilder().setState("PASSING").setRobotInDoorway(true).build())
            }

            override fun advanceSimulation(request: AdvanceDoorSimulationRequest, observer: StreamObserver<DoorStatus>) {
                advancedState.set(request.expectedState)
                observer.respond(readyStatus.toBuilder().setState("CLOSING").setPassageAllowed(false).build())
            }
        }) { mvc ->
            mvc.perform(post("/api/door/request-passage"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("OPENING"))
                .andExpect(jsonPath("$.doorOpen").value(false))
                .andExpect(jsonPath("$.passageAllowed").value(false))
            mvc.perform(post("/api/door/start-passage"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("PASSING"))
                .andExpect(jsonPath("$.robotInDoorway").value(true))
            mvc.perform(post("/api/door/simulation/advance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedState\":\"PASSING\"}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("CLOSING"))
                .andExpect(jsonPath("$.doorOpen").value(true))
                .andExpect(jsonPath("$.passageAllowed").value(false))
                .andExpect(jsonPath("$.robotInDoorway").value(false))
            assertThat(requests.get()).isEqualTo(1)
            assertThat(starts.get()).isEqualTo(1)
            assertThat(advancedState.get()).isEqualTo("PASSING")
        }
    }

    @Test
    fun `missing null blank nonstring and malformed event bodies never reach RPC`() {
        val events = AtomicInteger()
        withService(object : DoorServiceGrpc.DoorServiceImplBase() {
            override fun advanceSimulation(request: AdvanceDoorSimulationRequest, observer: StreamObserver<DoorStatus>) {
                events.incrementAndGet()
                observer.respond(readyStatus)
            }
        }) { mvc ->
            for (body in listOf("{}", "{\"expectedState\":null}", "{\"expectedState\":1}",
                "{\"expectedState\":\"\"}", "{\"expectedState\":\"  \"}", "[]", "null", "{", "")) {
                mvc.perform(post("/api/door/simulation/advance").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("DOOR_INVALID_REQUEST"))
            }
            assertThat(events.get()).isZero()
        }
    }

    @Test
    fun `unknown state maps to 400 and stale state or premature passage maps to 409`() {
        withService(object : DoorServiceGrpc.DoorServiceImplBase() {
            override fun advanceSimulation(request: AdvanceDoorSimulationRequest, observer: StreamObserver<DoorStatus>) {
                val error = if (request.expectedState == "UNKNOWN") Status.INVALID_ARGUMENT else Status.FAILED_PRECONDITION
                observer.onError(error.withDescription("private adapter diagnostic").asRuntimeException())
            }

            override fun startPassage(request: StartDoorPassageRequest, observer: StreamObserver<DoorStatus>) {
                observer.onError(Status.FAILED_PRECONDITION.asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(post("/api/door/simulation/advance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedState\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("DOOR_INVALID_ARGUMENT"))
            mvc.perform(post("/api/door/simulation/advance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedState\":\"OPENING\"}"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("DOOR_INVALID_STATE"))
            mvc.perform(post("/api/door/start-passage"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.message").value("Kapı bu işleme hazır değil. Güncel durumu kontrol edip beklenen adımı tamamlayın."))
        }
    }

    @Test
    fun `unavailable door service has a scoped 503 response`() {
        withService(object : DoorServiceGrpc.DoorServiceImplBase() {
            override fun getStatus(request: GetDoorStatusRequest, observer: StreamObserver<DoorStatus>) {
                observer.onError(Status.UNAVAILABLE.withDescription("private network detail").asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(get("/api/door/status"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DOOR_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Kapı simülatörüne ulaşılamıyor."))
        }
    }

    @Test
    fun `unresponsive door service hits the actual client deadline`() {
        withService(object : DoorServiceGrpc.DoorServiceImplBase() {
            override fun getStatus(request: GetDoorStatusRequest, observer: StreamObserver<DoorStatus>) {
                // Leave the RPC pending; the gateway must enforce its own timeout.
            }
        }) { mvc ->
            mvc.perform(get("/api/door/status"))
                .andExpect(status().isGatewayTimeout)
                .andExpect(jsonPath("$.code").value("DOOR_TIMEOUT"))
        }
    }

    private fun StreamObserver<DoorStatus>.respond(value: DoorStatus) {
        onNext(value)
        onCompleted()
    }

    private fun withService(service: DoorServiceGrpc.DoorServiceImplBase, block: (MockMvc) -> Unit) {
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
            .addService(service).build().start()
        try {
            RobotGateway("127.0.0.1", server.port).use { gateway ->
                val mvc = MockMvcBuilders.standaloneSetup(DoorController(gateway))
                    .setControllerAdvice(DoorErrorHandler(), ElevatorErrorHandler(), RobotErrorHandler()).build()
                block(mvc)
            }
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
