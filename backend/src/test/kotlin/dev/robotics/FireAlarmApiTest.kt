package dev.robotics

import dev.robotics.proto.ActivateFireAlarmRequest
import dev.robotics.proto.ClearFireAlarmRequest
import dev.robotics.proto.DoorServiceGrpc
import dev.robotics.proto.DoorStatus
import dev.robotics.proto.ElevatorServiceGrpc
import dev.robotics.proto.ElevatorStatus
import dev.robotics.proto.FireAlarmServiceGrpc
import dev.robotics.proto.FireAlarmStatus
import dev.robotics.proto.GetDoorStatusRequest
import dev.robotics.proto.GetElevatorStatusRequest
import dev.robotics.proto.GetFireAlarmStatusRequest
import dev.robotics.proto.ResetCancelledSimulationsRequest
import io.grpc.BindableService
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class FireAlarmApiTest {
    private val inactiveStatus = FireAlarmStatus.newBuilder()
        .setAlarmId("fire-alarm-01")
        .setMode("simulation")
        .setObservedAtUnixMs(1_700_000_000_000)
        .build()

    @Test
    fun `four routes reach distinct RPCs and clear retains cancelled recovery feedback`() {
        val calls = CopyOnWriteArrayList<String>()
        val cancelled = inactiveStatus.toBuilder().setActive(true).setRecoveryRequired(true)
            .setDoorCancelled(true).setElevatorCancelled(true).build()
        withServices(object : FireAlarmServiceGrpc.FireAlarmServiceImplBase() {
            override fun getStatus(request: GetFireAlarmStatusRequest, observer: StreamObserver<FireAlarmStatus>) {
                calls.add("status")
                observer.respond(inactiveStatus)
            }

            override fun activate(request: ActivateFireAlarmRequest, observer: StreamObserver<FireAlarmStatus>) {
                calls.add("activate")
                observer.respond(cancelled)
            }

            override fun clear(request: ClearFireAlarmRequest, observer: StreamObserver<FireAlarmStatus>) {
                calls.add("clear")
                observer.respond(cancelled.toBuilder().setActive(false).build())
            }

            override fun resetCancelledSimulations(request: ResetCancelledSimulationsRequest, observer: StreamObserver<FireAlarmStatus>) {
                calls.add("reset")
                observer.respond(inactiveStatus)
            }
        }) { mvc ->
            mvc.perform(get("/api/fire-alarm/status"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.alarmId").value("fire-alarm-01"))
                .andExpect(jsonPath("$.mode").value("simulation"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.recoveryRequired").value(false))
                .andExpect(jsonPath("$.observedAtUnixMs").value(1_700_000_000_000L))
            mvc.perform(post("/api/fire-alarm/simulation/activate"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.recoveryRequired").value(true))
                .andExpect(jsonPath("$.doorCancelled").value(true))
                .andExpect(jsonPath("$.elevatorCancelled").value(true))
            mvc.perform(post("/api/fire-alarm/simulation/clear"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.recoveryRequired").value(true))
                .andExpect(jsonPath("$.doorCancelled").value(true))
                .andExpect(jsonPath("$.elevatorCancelled").value(true))
            mvc.perform(post("/api/fire-alarm/simulation/reset-cancelled"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.recoveryRequired").value(false))
                .andExpect(jsonPath("$.doorCancelled").value(false))
                .andExpect(jsonPath("$.elevatorCancelled").value(false))
            assertThat(calls).containsExactly("status", "activate", "clear", "reset")
        }
    }

    @Test
    fun `cancelled door and elevator DTOs preserve sensor feedback and alarm flags`() {
        withServices(
            object : DoorServiceGrpc.DoorServiceImplBase() {
                override fun getStatus(request: GetDoorStatusRequest, observer: StreamObserver<DoorStatus>) {
                    observer.onNext(DoorStatus.newBuilder().setDoorId("door-01").setMode("simulation")
                        .setState("CANCELLED").setDoorOpen(true).setPassageAllowed(false).setRobotInDoorway(true)
                        .setFireAlarmActive(true).setRecoveryRequired(true).build())
                    observer.onCompleted()
                }
            },
            object : ElevatorServiceGrpc.ElevatorServiceImplBase() {
                override fun getStatus(request: GetElevatorStatusRequest, observer: StreamObserver<ElevatorStatus>) {
                    observer.onNext(ElevatorStatus.newBuilder().setElevatorId("elevator-01").setMode("simulation")
                        .setState("CANCELLED").setCurrentFloor(2).setPickupFloor(0).setDestinationFloor(4)
                        .setDoorsOpen(false).setRobotInCabin(true).setFireAlarmActive(true).setRecoveryRequired(true).build())
                    observer.onCompleted()
                }
            },
        ) { mvc ->
            mvc.perform(get("/api/door/status"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.doorOpen").value(true))
                .andExpect(jsonPath("$.robotInDoorway").value(true))
                .andExpect(jsonPath("$.passageAllowed").value(false))
                .andExpect(jsonPath("$.fireAlarmActive").value(true))
                .andExpect(jsonPath("$.recoveryRequired").value(true))
            mvc.perform(get("/api/elevator/status"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.currentFloor").value(2))
                .andExpect(jsonPath("$.destinationFloor").value(4))
                .andExpect(jsonPath("$.doorsOpen").value(false))
                .andExpect(jsonPath("$.robotInCabin").value(true))
                .andExpect(jsonPath("$.fireAlarmActive").value(true))
                .andExpect(jsonPath("$.recoveryRequired").value(true))
        }
    }

    @Test
    fun `reset blocked by active alarm returns scoped 409 without exposing internals`() {
        withServices(object : FireAlarmServiceGrpc.FireAlarmServiceImplBase() {
            override fun resetCancelledSimulations(request: ResetCancelledSimulationsRequest, observer: StreamObserver<FireAlarmStatus>) {
                observer.onError(Status.FAILED_PRECONDITION.withDescription("private adapter detail").asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(post("/api/fire-alarm/simulation/reset-cancelled"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("FIRE_ALARM_INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Alarm etkinken iptal edilen senaryolar sıfırlanamaz. Önce alarmı temizleyin."))
        }
    }

    @Test
    fun `invalid argument and unexpected downstream failure map to 400 and 502`() {
        withServices(object : FireAlarmServiceGrpc.FireAlarmServiceImplBase() {
            override fun activate(request: ActivateFireAlarmRequest, observer: StreamObserver<FireAlarmStatus>) {
                observer.onError(Status.INVALID_ARGUMENT.asRuntimeException())
            }

            override fun clear(request: ClearFireAlarmRequest, observer: StreamObserver<FireAlarmStatus>) {
                observer.onError(Status.INTERNAL.asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(post("/api/fire-alarm/simulation/activate"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("FIRE_ALARM_INVALID_ARGUMENT"))
            mvc.perform(post("/api/fire-alarm/simulation/clear"))
                .andExpect(status().isBadGateway)
                .andExpect(jsonPath("$.code").value("FIRE_ALARM_ERROR"))
        }
    }

    @Test
    fun `unavailable fire alarm service returns scoped 503`() {
        withServices(object : FireAlarmServiceGrpc.FireAlarmServiceImplBase() {
            override fun getStatus(request: GetFireAlarmStatusRequest, observer: StreamObserver<FireAlarmStatus>) {
                observer.onError(Status.UNAVAILABLE.asRuntimeException())
            }
        }) { mvc ->
            mvc.perform(get("/api/fire-alarm/status"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("FIRE_ALARM_UNAVAILABLE"))
        }
    }

    @Test
    fun `unresponsive fire alarm service hits actual client deadline`() {
        withServices(object : FireAlarmServiceGrpc.FireAlarmServiceImplBase() {
            override fun getStatus(request: GetFireAlarmStatusRequest, observer: StreamObserver<FireAlarmStatus>) {
                // Leave pending so the gateway's two-second deadline must end the RPC.
            }
        }) { mvc ->
            mvc.perform(get("/api/fire-alarm/status"))
                .andExpect(status().isGatewayTimeout)
                .andExpect(jsonPath("$.code").value("FIRE_ALARM_TIMEOUT"))
        }
    }

    private fun StreamObserver<FireAlarmStatus>.respond(value: FireAlarmStatus) {
        onNext(value)
        onCompleted()
    }

    private fun withServices(vararg services: BindableService, block: (MockMvc) -> Unit) {
        val builder = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
        services.forEach { builder.addService(it) }
        val server = builder.build().start()
        try {
            RobotGateway("127.0.0.1", server.port).use { gateway ->
                val mvc = MockMvcBuilders.standaloneSetup(FireAlarmController(gateway), DoorController(gateway), ElevatorController(gateway))
                    .setControllerAdvice(FireAlarmErrorHandler(), DoorErrorHandler(), ElevatorErrorHandler(), RobotErrorHandler()).build()
                block(mvc)
            }
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
