//! Shared alarm for the building simulators; no physical alarm or ROS command is sent.

use std::{sync::Arc, time::SystemTime};

use tokio::sync::RwLock;
use tonic::{Request, Response, Status};

use crate::{
    door::DoorSimulator,
    elevator::ElevatorSimulator,
    proto::{
        fire_alarm_service_server::FireAlarmService, ActivateFireAlarmRequest,
        ClearFireAlarmRequest, FireAlarmStatus, GetFireAlarmStatusRequest,
        ResetCancelledSimulationsRequest,
    },
};

pub type AlarmGate = Arc<RwLock<bool>>;

pub fn alarm_active_error() -> Status {
    Status::failed_precondition("Yangın alarmı etkin. Normal bina komutları engellendi.")
}

#[derive(Clone)]
pub struct FireAlarmSimulator {
    alarm: AlarmGate,
    door: DoorSimulator,
    elevator: ElevatorSimulator,
}

impl FireAlarmSimulator {
    pub fn new(alarm: AlarmGate, door: DoorSimulator, elevator: ElevatorSimulator) -> Self {
        Self {
            alarm,
            door,
            elevator,
        }
    }

    // Caller holds the shared read/write gate. Domain helpers lock only their
    // own mutex, so no nested gate acquisition can deadlock a waiting writer.
    async fn snapshot_under_gate(&self, active: bool) -> FireAlarmStatus {
        let door_cancelled = self.door.is_cancelled_under_gate().await;
        let elevator_cancelled = self.elevator.is_cancelled_under_gate().await;
        FireAlarmStatus {
            alarm_id: "fire-alarm-01".into(),
            mode: "simulation".into(),
            active,
            recovery_required: door_cancelled || elevator_cancelled,
            door_cancelled,
            elevator_cancelled,
            observed_at_unix_ms: SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64,
        }
    }
}

#[tonic::async_trait]
impl FireAlarmService for FireAlarmSimulator {
    async fn get_status(
        &self,
        _request: Request<GetFireAlarmStatusRequest>,
    ) -> Result<Response<FireAlarmStatus>, Status> {
        let alarm = self.alarm.read().await;
        Ok(Response::new(self.snapshot_under_gate(*alarm).await))
    }

    async fn activate(
        &self,
        _request: Request<ActivateFireAlarmRequest>,
    ) -> Result<Response<FireAlarmStatus>, Status> {
        let mut alarm = self.alarm.write().await;
        *alarm = true;
        self.door.cancel_for_alarm().await;
        self.elevator.cancel_for_alarm().await;
        Ok(Response::new(self.snapshot_under_gate(*alarm).await))
    }

    async fn clear(
        &self,
        _request: Request<ClearFireAlarmRequest>,
    ) -> Result<Response<FireAlarmStatus>, Status> {
        let mut alarm = self.alarm.write().await;
        *alarm = false;
        // Clearing the alarm does not resume or reset either cancelled case.
        Ok(Response::new(self.snapshot_under_gate(*alarm).await))
    }

    async fn reset_cancelled_simulations(
        &self,
        _request: Request<ResetCancelledSimulationsRequest>,
    ) -> Result<Response<FireAlarmStatus>, Status> {
        let alarm = self.alarm.write().await;
        if *alarm {
            return Err(Status::failed_precondition(
                "Simülasyonları sıfırlamadan önce yangın alarmını kaldırın.",
            ));
        }
        // This explicit simulation-only reset is not physical recovery feedback.
        self.door.reset_cancelled_under_gate().await;
        self.elevator.reset_cancelled_under_gate().await;
        Ok(Response::new(self.snapshot_under_gate(*alarm).await))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{
        door_service_server::DoorService, elevator_service_server::ElevatorService,
        AdvanceDoorSimulationRequest, AdvanceElevatorSimulationRequest, CallElevatorRequest,
        DoorStatus, ElevatorStatus, GetDoorStatusRequest, GetElevatorStatusRequest,
        RequestDoorPassageRequest, SelectFloorRequest, StartDoorPassageRequest,
    };
    use tonic::Code;

    fn building() -> (FireAlarmSimulator, DoorSimulator, ElevatorSimulator) {
        let gate = AlarmGate::default();
        let door = DoorSimulator::new(gate.clone());
        let elevator = ElevatorSimulator::new(gate.clone());
        (
            FireAlarmSimulator::new(gate, door.clone(), elevator.clone()),
            door,
            elevator,
        )
    }

    async fn door_status(door: &DoorSimulator) -> DoorStatus {
        door.get_status(Request::new(GetDoorStatusRequest {}))
            .await
            .unwrap()
            .into_inner()
    }

    async fn elevator_status(elevator: &ElevatorSimulator) -> ElevatorStatus {
        elevator
            .get_status(Request::new(GetElevatorStatusRequest {}))
            .await
            .unwrap()
            .into_inner()
    }

    fn comparable_door(mut status: DoorStatus) -> DoorStatus {
        status.observed_at_unix_ms = 0;
        status
    }

    fn comparable_elevator(mut status: ElevatorStatus) -> ElevatorStatus {
        status.observed_at_unix_ms = 0;
        status
    }

    async fn passing_door(door: &DoorSimulator) {
        door.request_passage(Request::new(RequestDoorPassageRequest {}))
            .await
            .unwrap();
        door.advance_simulation(Request::new(AdvanceDoorSimulationRequest {
            expected_state: "OPENING".into(),
        }))
        .await
        .unwrap();
        door.start_passage(Request::new(StartDoorPassageRequest {}))
            .await
            .unwrap();
    }

    async fn moving_elevator(elevator: &ElevatorSimulator) {
        elevator
            .call(Request::new(CallElevatorRequest { pickup_floor: 2 }))
            .await
            .unwrap();
        for expected in ["CALLING", "WAITING_FOR_BOARDING"] {
            elevator
                .advance_simulation(Request::new(AdvanceElevatorSimulationRequest {
                    expected_state: expected.into(),
                }))
                .await
                .unwrap();
        }
        elevator
            .select_floor(Request::new(SelectFloorRequest {
                destination_floor: 5,
            }))
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn alarm_cancels_both_cases_without_inventing_sensor_feedback() {
        let (fire, door, elevator) = building();
        passing_door(&door).await;
        moving_elevator(&elevator).await;
        let mut expected_door = door_status(&door).await;
        let mut expected_elevator = elevator_status(&elevator).await;

        let alarm = fire
            .activate(Request::new(ActivateFireAlarmRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(alarm.mode, "simulation");
        assert!(
            alarm.active
                && alarm.recovery_required
                && alarm.door_cancelled
                && alarm.elevator_cancelled
        );
        expected_door.state = "CANCELLED".into();
        expected_door.passage_allowed = false;
        expected_door.fire_alarm_active = true;
        expected_door.recovery_required = true;
        expected_elevator.state = "CANCELLED".into();
        expected_elevator.fire_alarm_active = true;
        expected_elevator.recovery_required = true;
        assert_eq!(
            comparable_door(door_status(&door).await),
            comparable_door(expected_door.clone())
        );
        assert_eq!(
            comparable_elevator(elevator_status(&elevator).await),
            comparable_elevator(expected_elevator.clone())
        );

        assert_eq!(
            door.request_passage(Request::new(RequestDoorPassageRequest {}))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );
        assert_eq!(
            door.start_passage(Request::new(StartDoorPassageRequest {}))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );
        assert_eq!(
            door.advance_simulation(Request::new(AdvanceDoorSimulationRequest {
                expected_state: "PASSING".into()
            }))
            .await
            .unwrap_err()
            .code(),
            Code::FailedPrecondition
        );
        assert_eq!(
            elevator
                .call(Request::new(CallElevatorRequest { pickup_floor: 1 }))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );
        assert_eq!(
            elevator
                .select_floor(Request::new(SelectFloorRequest {
                    destination_floor: 3
                }))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );
        assert_eq!(
            elevator
                .advance_simulation(Request::new(AdvanceElevatorSimulationRequest {
                    expected_state: "MOVING".into()
                }))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );
        assert_eq!(
            fire.reset_cancelled_simulations(Request::new(ResetCancelledSimulationsRequest {}))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );
        fire.activate(Request::new(ActivateFireAlarmRequest {}))
            .await
            .unwrap();
        assert_eq!(
            comparable_door(door_status(&door).await),
            comparable_door(expected_door.clone())
        );
        assert_eq!(
            comparable_elevator(elevator_status(&elevator).await),
            comparable_elevator(expected_elevator.clone())
        );

        for _ in 0..2 {
            let cleared = fire
                .clear(Request::new(ClearFireAlarmRequest {}))
                .await
                .unwrap()
                .into_inner();
            assert!(!cleared.active);
            assert!(
                cleared.recovery_required && cleared.door_cancelled && cleared.elevator_cancelled
            );
        }
        expected_door.fire_alarm_active = false;
        expected_elevator.fire_alarm_active = false;
        assert_eq!(
            comparable_door(door_status(&door).await),
            comparable_door(expected_door)
        );
        assert_eq!(
            comparable_elevator(elevator_status(&elevator).await),
            comparable_elevator(expected_elevator)
        );
        assert_eq!(
            door.start_passage(Request::new(StartDoorPassageRequest {}))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );
        assert_eq!(
            elevator
                .advance_simulation(Request::new(AdvanceElevatorSimulationRequest {
                    expected_state: "MOVING".into()
                }))
                .await
                .unwrap_err()
                .code(),
            Code::FailedPrecondition
        );

        let reset = fire
            .reset_cancelled_simulations(Request::new(ResetCancelledSimulationsRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert!(
            !reset.active
                && !reset.recovery_required
                && !reset.door_cancelled
                && !reset.elevator_cancelled
        );
        let recovered_door = door_status(&door).await;
        assert_eq!(recovered_door.state, "IDLE");
        assert!(
            !recovered_door.door_open
                && !recovered_door.robot_in_doorway
                && !recovered_door.passage_allowed
        );
        let recovered_elevator = elevator_status(&elevator).await;
        assert_eq!(recovered_elevator.state, "IDLE");
        assert_eq!(recovered_elevator.current_floor, 2);
        assert_eq!(recovered_elevator.pickup_floor, None);
        assert_eq!(recovered_elevator.destination_floor, None);
        assert!(!recovered_elevator.doors_open && !recovered_elevator.robot_in_cabin);
        door.request_passage(Request::new(RequestDoorPassageRequest {}))
            .await
            .unwrap();
        elevator
            .call(Request::new(CallElevatorRequest { pickup_floor: 0 }))
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn idle_and_completed_cases_are_blocked_but_not_cancelled() {
        let (fire, door, elevator) = building();
        for completed in [false, true] {
            if completed {
                passing_door(&door).await;
                for expected in ["PASSING", "CLOSING"] {
                    door.advance_simulation(Request::new(AdvanceDoorSimulationRequest {
                        expected_state: expected.into(),
                    }))
                    .await
                    .unwrap();
                }
                moving_elevator(&elevator).await;
                for expected in ["MOVING", "WAITING_FOR_EXIT"] {
                    elevator
                        .advance_simulation(Request::new(AdvanceElevatorSimulationRequest {
                            expected_state: expected.into(),
                        }))
                        .await
                        .unwrap();
                }
            }
            let before_door = door_status(&door).await;
            let before_elevator = elevator_status(&elevator).await;
            let active = fire
                .activate(Request::new(ActivateFireAlarmRequest {}))
                .await
                .unwrap()
                .into_inner();
            assert!(active.active);
            assert!(
                !active.recovery_required && !active.door_cancelled && !active.elevator_cancelled
            );
            assert_eq!(door_status(&door).await.state, before_door.state);
            assert_eq!(
                elevator_status(&elevator).await.state,
                before_elevator.state
            );
            assert_eq!(
                door.request_passage(Request::new(RequestDoorPassageRequest {}))
                    .await
                    .unwrap_err()
                    .code(),
                Code::FailedPrecondition
            );
            assert_eq!(
                elevator
                    .call(Request::new(CallElevatorRequest { pickup_floor: 0 }))
                    .await
                    .unwrap_err()
                    .code(),
                Code::FailedPrecondition
            );
            fire.clear(Request::new(ClearFireAlarmRequest {}))
                .await
                .unwrap();
            fire.reset_cancelled_simulations(Request::new(ResetCancelledSimulationsRequest {}))
                .await
                .unwrap();
            assert_eq!(
                comparable_door(door_status(&door).await),
                comparable_door(before_door)
            );
            assert_eq!(
                comparable_elevator(elevator_status(&elevator).await),
                comparable_elevator(before_elevator)
            );
        }
    }

    #[tokio::test]
    async fn reset_only_changes_cancelled_cases_and_preserves_other_active_work() {
        let (fire, door, elevator) = building();
        passing_door(&door).await;
        fire.activate(Request::new(ActivateFireAlarmRequest {}))
            .await
            .unwrap();
        fire.clear(Request::new(ClearFireAlarmRequest {}))
            .await
            .unwrap();
        elevator
            .call(Request::new(CallElevatorRequest { pickup_floor: 4 }))
            .await
            .unwrap();
        let before = elevator_status(&elevator).await;
        let reset = fire
            .reset_cancelled_simulations(Request::new(ResetCancelledSimulationsRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert!(!reset.recovery_required);
        assert_eq!(door_status(&door).await.state, "IDLE");
        assert_eq!(
            comparable_elevator(elevator_status(&elevator).await),
            comparable_elevator(before)
        );

        fire.activate(Request::new(ActivateFireAlarmRequest {}))
            .await
            .unwrap();
        fire.clear(Request::new(ClearFireAlarmRequest {}))
            .await
            .unwrap();
        door.request_passage(Request::new(RequestDoorPassageRequest {}))
            .await
            .unwrap();
        let before = door_status(&door).await;
        fire.reset_cancelled_simulations(Request::new(ResetCancelledSimulationsRequest {}))
            .await
            .unwrap();
        assert_eq!(elevator_status(&elevator).await.state, "IDLE");
        assert_eq!(
            comparable_door(door_status(&door).await),
            comparable_door(before)
        );
    }

    #[tokio::test]
    async fn activation_racing_commands_serializes_on_the_same_gate() {
        let (fire, door, elevator) = building();
        passing_door(&door).await;
        moving_elevator(&elevator).await;
        // The command may win before activation, or be rejected after it. In
        // either order all later commands are blocked and sensors remain fixed.
        let (door_result, activated, elevator_result) = tokio::join!(
            door.advance_simulation(Request::new(AdvanceDoorSimulationRequest {
                expected_state: "PASSING".into()
            })),
            fire.activate(Request::new(ActivateFireAlarmRequest {})),
            elevator.advance_simulation(Request::new(AdvanceElevatorSimulationRequest {
                expected_state: "MOVING".into()
            })),
        );
        activated.unwrap();
        for result in [door_result.map(|_| ()), elevator_result.map(|_| ())] {
            if let Err(error) = result {
                assert_eq!(error.code(), Code::FailedPrecondition);
            }
        }
        let before_door = door_status(&door).await;
        let before_elevator = elevator_status(&elevator).await;
        assert_eq!(before_door.state, "CANCELLED");
        assert_eq!(before_elevator.state, "CANCELLED");
        assert!(before_door.fire_alarm_active && before_elevator.fire_alarm_active);
        let (door_result, elevator_result, observed) = tokio::join!(
            door.advance_simulation(Request::new(AdvanceDoorSimulationRequest {
                expected_state: "CLOSING".into()
            })),
            elevator.advance_simulation(Request::new(AdvanceElevatorSimulationRequest {
                expected_state: "WAITING_FOR_EXIT".into()
            })),
            fire.get_status(Request::new(GetFireAlarmStatusRequest {})),
        );
        assert_eq!(door_result.unwrap_err().code(), Code::FailedPrecondition);
        assert_eq!(
            elevator_result.unwrap_err().code(),
            Code::FailedPrecondition
        );
        assert!(observed.unwrap().into_inner().active);
        assert_eq!(
            comparable_door(door_status(&door).await),
            comparable_door(before_door)
        );
        assert_eq!(
            comparable_elevator(elevator_status(&elevator).await),
            comparable_elevator(before_elevator)
        );
    }
}
