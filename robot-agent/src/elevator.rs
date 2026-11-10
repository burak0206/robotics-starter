//! Independent, in-memory building simulator. No ROS or vendor command is sent.

use std::{sync::Arc, time::SystemTime};

use tokio::sync::Mutex;
use tonic::{Request, Response, Status};

use crate::fire_alarm::{alarm_active_error, AlarmGate};

use crate::proto::{
    elevator_service_server::ElevatorService, AdvanceElevatorSimulationRequest,
    CallElevatorRequest, ElevatorStatus, GetElevatorStatusRequest, SelectFloorRequest,
};

#[derive(Debug)]
enum ElevatorError {
    RecoveryRequired,
    InvalidFloor,
    Busy,
    BoardingNotConfirmed,
    SameFloor,
    MissingPickup,
    MissingDestination,
    InvalidExpectedState,
    StaleExpectedState,
    NoSimulationStep,
}

impl From<ElevatorError> for Status {
    fn from(error: ElevatorError) -> Self {
        match error {
            ElevatorError::RecoveryRequired => Self::failed_precondition(
                "Asansör geçişi iptal edildi. Simülasyonu açıkça sıfırlamadan devam edilemez.",
            ),
            ElevatorError::InvalidFloor => {
                Self::invalid_argument("Kat 0 ile 5 arasında olmalıdır.")
            }
            ElevatorError::Busy => {
                Self::failed_precondition("Asansörde devam eden bir geçiş var.")
            }
            ElevatorError::BoardingNotConfirmed => Self::failed_precondition(
                "Hedef katı seçmeden önce kapının açılması ve robotun kabine girmesi doğrulanmalıdır.",
            ),
            ElevatorError::SameFloor => {
                Self::invalid_argument("Hedef kat çağrı katından farklı olmalıdır.")
            }
            ElevatorError::MissingPickup => Self::internal("Çağrı katı tanımlı değil."),
            ElevatorError::MissingDestination => Self::internal("Hedef kat tanımlı değil."),
            ElevatorError::InvalidExpectedState => {
                Self::invalid_argument("İlerletilecek mevcut simülasyon adımı belirtilmelidir.")
            }
            ElevatorError::StaleExpectedState => Self::failed_precondition(
                "Simülasyon adımı değişti. Güncel durumu aldıktan sonra tekrar deneyin.",
            ),
            ElevatorError::NoSimulationStep => Self::failed_precondition(
                "Bu adımda simülasyon ilerletilemez; önce uygun komutu gönderin.",
            ),
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
enum Phase {
    #[default]
    Idle,
    Calling,
    WaitingForBoarding,
    ReadyForDestination,
    Moving,
    WaitingForExit,
    Completed,
}

impl Phase {
    fn parse(name: &str) -> Option<Self> {
        match name {
            "IDLE" => Some(Self::Idle),
            "CALLING" => Some(Self::Calling),
            "WAITING_FOR_BOARDING" => Some(Self::WaitingForBoarding),
            "READY_FOR_DESTINATION" => Some(Self::ReadyForDestination),
            "MOVING" => Some(Self::Moving),
            "WAITING_FOR_EXIT" => Some(Self::WaitingForExit),
            "COMPLETED" => Some(Self::Completed),
            _ => None,
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Idle => "IDLE",
            Self::Calling => "CALLING",
            Self::WaitingForBoarding => "WAITING_FOR_BOARDING",
            Self::ReadyForDestination => "READY_FOR_DESTINATION",
            Self::Moving => "MOVING",
            Self::WaitingForExit => "WAITING_FOR_EXIT",
            Self::Completed => "COMPLETED",
        }
    }
}

#[derive(Default)]
struct Elevator {
    phase: Phase,
    current_floor: i32,
    pickup_floor: Option<i32>,
    destination_floor: Option<i32>,
    cancelled: bool,
}

impl Elevator {
    fn snapshot(&self, fire_alarm_active: bool) -> ElevatorStatus {
        ElevatorStatus {
            elevator_id: "elevator-01".into(),
            mode: "simulation".into(),
            state: if self.cancelled {
                "CANCELLED"
            } else {
                self.phase.name()
            }
            .into(),
            current_floor: self.current_floor,
            pickup_floor: self.pickup_floor,
            destination_floor: self.destination_floor,
            doors_open: matches!(
                self.phase,
                Phase::WaitingForBoarding | Phase::ReadyForDestination | Phase::WaitingForExit
            ),
            robot_in_cabin: matches!(
                self.phase,
                Phase::ReadyForDestination | Phase::Moving | Phase::WaitingForExit
            ),
            observed_at_unix_ms: SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64,
            fire_alarm_active,
            recovery_required: self.cancelled,
        }
    }

    fn check_floor(floor: i32) -> Result<(), ElevatorError> {
        if !(0..=5).contains(&floor) {
            return Err(ElevatorError::InvalidFloor);
        }
        Ok(())
    }

    fn call(&mut self, pickup_floor: i32) -> Result<(), ElevatorError> {
        self.ensure_recovered()?;
        Self::check_floor(pickup_floor)?;
        if !matches!(self.phase, Phase::Idle | Phase::Completed) {
            return Err(ElevatorError::Busy);
        }
        self.pickup_floor = Some(pickup_floor);
        self.destination_floor = None;
        self.phase = Phase::Calling;
        Ok(())
    }

    fn select_floor(&mut self, destination_floor: i32) -> Result<(), ElevatorError> {
        self.ensure_recovered()?;
        Self::check_floor(destination_floor)?;
        if self.phase != Phase::ReadyForDestination {
            return Err(ElevatorError::BoardingNotConfirmed);
        }
        if self.pickup_floor == Some(destination_floor) {
            return Err(ElevatorError::SameFloor);
        }
        self.destination_floor = Some(destination_floor);
        self.phase = Phase::Moving;
        Ok(())
    }

    fn advance(&mut self, expected_state: &str) -> Result<(), ElevatorError> {
        self.ensure_recovered()?;
        let expected = Phase::parse(expected_state).ok_or(ElevatorError::InvalidExpectedState)?;
        if self.phase != expected {
            return Err(ElevatorError::StaleExpectedState);
        }
        self.phase = match self.phase {
            Phase::Calling => {
                self.current_floor = self.pickup_floor.ok_or(ElevatorError::MissingPickup)?;
                Phase::WaitingForBoarding
            }
            Phase::WaitingForBoarding => Phase::ReadyForDestination,
            Phase::Moving => {
                self.current_floor = self
                    .destination_floor
                    .ok_or(ElevatorError::MissingDestination)?;
                Phase::WaitingForExit
            }
            Phase::WaitingForExit => Phase::Completed,
            _ => {
                return Err(ElevatorError::NoSimulationStep);
            }
        };
        Ok(())
    }

    fn ensure_recovered(&self) -> Result<(), ElevatorError> {
        if self.cancelled {
            return Err(ElevatorError::RecoveryRequired);
        }
        Ok(())
    }
}

#[derive(Clone, Default)]
pub struct ElevatorSimulator {
    elevator: Arc<Mutex<Elevator>>,
    alarm: AlarmGate,
}

impl ElevatorSimulator {
    pub fn new(alarm: AlarmGate) -> Self {
        Self {
            elevator: Arc::default(),
            alarm,
        }
    }

    // These helpers acquire only the domain mutex; the caller owns the alarm gate.
    pub(super) async fn cancel_for_alarm(&self) {
        let mut elevator = self.elevator.lock().await;
        if !matches!(elevator.phase, Phase::Idle | Phase::Completed) {
            elevator.cancelled = true;
        }
    }

    pub(super) async fn is_cancelled_under_gate(&self) -> bool {
        self.elevator.lock().await.cancelled
    }

    pub(super) async fn reset_cancelled_under_gate(&self) {
        let mut elevator = self.elevator.lock().await;
        if elevator.cancelled {
            let current_floor = elevator.current_floor;
            *elevator = Elevator {
                current_floor,
                ..Elevator::default()
            };
        }
    }
}

#[tonic::async_trait]
impl ElevatorService for ElevatorSimulator {
    async fn get_status(
        &self,
        _request: Request<GetElevatorStatusRequest>,
    ) -> Result<Response<ElevatorStatus>, Status> {
        let alarm = self.alarm.read().await;
        Ok(Response::new(self.elevator.lock().await.snapshot(*alarm)))
    }

    async fn call(
        &self,
        request: Request<CallElevatorRequest>,
    ) -> Result<Response<ElevatorStatus>, Status> {
        let alarm = self.alarm.read().await;
        if *alarm {
            return Err(alarm_active_error());
        }
        let mut elevator = self.elevator.lock().await;
        elevator.call(request.into_inner().pickup_floor)?;
        Ok(Response::new(elevator.snapshot(*alarm)))
    }

    async fn select_floor(
        &self,
        request: Request<SelectFloorRequest>,
    ) -> Result<Response<ElevatorStatus>, Status> {
        let alarm = self.alarm.read().await;
        if *alarm {
            return Err(alarm_active_error());
        }
        let mut elevator = self.elevator.lock().await;
        elevator.select_floor(request.into_inner().destination_floor)?;
        Ok(Response::new(elevator.snapshot(*alarm)))
    }

    async fn advance_simulation(
        &self,
        request: Request<AdvanceElevatorSimulationRequest>,
    ) -> Result<Response<ElevatorStatus>, Status> {
        let alarm = self.alarm.read().await;
        if *alarm {
            return Err(alarm_active_error());
        }
        let mut elevator = self.elevator.lock().await;
        elevator.advance(&request.into_inner().expected_state)?;
        Ok(Response::new(elevator.snapshot(*alarm)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tonic::Code;

    fn error_code(result: Result<(), ElevatorError>) -> Code {
        Status::from(result.unwrap_err()).code()
    }

    fn semantic_snapshot(elevator: &Elevator) -> ElevatorStatus {
        let mut status = elevator.snapshot(false);
        status.observed_at_unix_ms = 0;
        status
    }

    #[test]
    fn lifecycle_requires_boarding_before_destination_and_exit_before_reuse() {
        let mut elevator = Elevator::default();
        let initial = elevator.snapshot(false);
        assert_eq!(initial.mode, "simulation");
        assert_eq!(initial.state, "IDLE");
        assert_eq!(initial.current_floor, 0);
        assert_eq!(initial.pickup_floor, None);
        assert!(!initial.doors_open);
        assert!(!initial.robot_in_cabin);
        assert!(initial.observed_at_unix_ms > 0);

        elevator.call(2).unwrap();
        assert_eq!(elevator.phase, Phase::Calling);
        assert_eq!(elevator.current_floor, 0);
        assert!(!elevator.snapshot(false).doors_open);

        elevator.advance("CALLING").unwrap();
        assert_eq!(elevator.current_floor, 2);
        assert_eq!(elevator.phase, Phase::WaitingForBoarding);
        assert!(elevator.snapshot(false).doors_open);
        assert!(!elevator.snapshot(false).robot_in_cabin);

        elevator.advance("WAITING_FOR_BOARDING").unwrap();
        assert_eq!(elevator.phase, Phase::ReadyForDestination);
        assert!(elevator.snapshot(false).doors_open);
        assert!(elevator.snapshot(false).robot_in_cabin);

        elevator.select_floor(5).unwrap();
        assert_eq!(elevator.phase, Phase::Moving);
        assert_eq!(elevator.current_floor, 2);
        assert_eq!(elevator.destination_floor, Some(5));
        assert!(!elevator.snapshot(false).doors_open);
        assert!(elevator.snapshot(false).robot_in_cabin);

        elevator.advance("MOVING").unwrap();
        assert_eq!(elevator.phase, Phase::WaitingForExit);
        assert_eq!(elevator.current_floor, 5);
        assert!(elevator.snapshot(false).doors_open);
        assert!(elevator.snapshot(false).robot_in_cabin);
        assert_eq!(error_code(elevator.call(0)), Code::FailedPrecondition);

        elevator.advance("WAITING_FOR_EXIT").unwrap();
        assert_eq!(elevator.phase, Phase::Completed);
        assert!(!elevator.snapshot(false).doors_open);
        assert!(!elevator.snapshot(false).robot_in_cabin);

        elevator.call(0).unwrap();
        assert_eq!(elevator.current_floor, 5);
        assert_eq!(elevator.pickup_floor, Some(0));
        assert_eq!(elevator.destination_floor, None);
        elevator.advance("CALLING").unwrap();
        elevator.advance("WAITING_FOR_BOARDING").unwrap();
        elevator.select_floor(1).unwrap();
        elevator.advance("MOVING").unwrap();
        elevator.advance("WAITING_FOR_EXIT").unwrap();
        assert_eq!(elevator.current_floor, 1);
        assert_eq!(elevator.phase, Phase::Completed);
    }

    #[test]
    fn invalid_commands_leave_state_unchanged() {
        let mut elevator = Elevator::default();
        for floor in [-1, 6, i32::MIN, i32::MAX] {
            let before = semantic_snapshot(&elevator);
            assert_eq!(error_code(elevator.call(floor)), Code::InvalidArgument);
            assert_eq!(
                error_code(elevator.select_floor(floor)),
                Code::InvalidArgument
            );
            assert_eq!(semantic_snapshot(&elevator), before);
        }

        let before = semantic_snapshot(&elevator);
        assert_eq!(
            error_code(elevator.select_floor(1)),
            Code::FailedPrecondition
        );
        assert_eq!(
            error_code(elevator.advance("IDLE")),
            Code::FailedPrecondition
        );
        assert_eq!(semantic_snapshot(&elevator), before);

        elevator.call(0).unwrap();
        let before = semantic_snapshot(&elevator);
        assert_eq!(error_code(elevator.call(2)), Code::FailedPrecondition);
        assert_eq!(
            error_code(elevator.select_floor(1)),
            Code::FailedPrecondition
        );
        assert_eq!(semantic_snapshot(&elevator), before);

        elevator.advance("CALLING").unwrap();
        let before = semantic_snapshot(&elevator);
        assert_eq!(
            error_code(elevator.select_floor(1)),
            Code::FailedPrecondition
        );
        assert_eq!(semantic_snapshot(&elevator), before);

        elevator.advance("WAITING_FOR_BOARDING").unwrap();
        let before = semantic_snapshot(&elevator);
        assert_eq!(
            error_code(elevator.advance("READY_FOR_DESTINATION")),
            Code::FailedPrecondition
        );
        assert_eq!(error_code(elevator.select_floor(0)), Code::InvalidArgument);
        assert_eq!(semantic_snapshot(&elevator), before);
    }

    #[test]
    fn ground_floor_is_a_present_valid_destination() {
        let mut elevator = Elevator::default();
        elevator.call(5).unwrap();
        elevator.advance("CALLING").unwrap();
        elevator.advance("WAITING_FOR_BOARDING").unwrap();
        elevator.select_floor(0).unwrap();
        assert_eq!(elevator.snapshot(false).destination_floor, Some(0));
        elevator.advance("MOVING").unwrap();
        assert_eq!(elevator.current_floor, 0);
    }

    #[tokio::test]
    async fn simultaneous_calls_accept_exactly_one_and_return_atomic_status() {
        let simulator = ElevatorSimulator::default();
        let (first, second) = tokio::join!(
            simulator.call(Request::new(CallElevatorRequest { pickup_floor: 2 })),
            simulator.call(Request::new(CallElevatorRequest { pickup_floor: 4 })),
        );
        let outcomes = [first, second];
        assert_eq!(outcomes.iter().filter(|result| result.is_ok()).count(), 1);
        let accepted = outcomes
            .iter()
            .find_map(|result| result.as_ref().ok())
            .unwrap()
            .get_ref();
        let rejected = outcomes
            .iter()
            .find_map(|result| result.as_ref().err())
            .unwrap();
        assert_eq!(rejected.code(), Code::FailedPrecondition);
        let final_status = simulator
            .get_status(Request::new(GetElevatorStatusRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(final_status.state, "CALLING");
        assert_eq!(final_status.pickup_floor, accepted.pickup_floor);
        assert_eq!(final_status.destination_floor, None);
    }

    #[test]
    fn missing_unknown_or_stale_expected_state_never_advances() {
        let mut elevator = Elevator::default();
        elevator.call(2).unwrap();
        let before = semantic_snapshot(&elevator);
        for invalid in ["", "UNKNOWN", "calling"] {
            assert_eq!(error_code(elevator.advance(invalid)), Code::InvalidArgument);
            assert_eq!(semantic_snapshot(&elevator), before);
        }
        for stale in ["IDLE", "MOVING", "COMPLETED"] {
            assert_eq!(
                error_code(elevator.advance(stale)),
                Code::FailedPrecondition
            );
            assert_eq!(semantic_snapshot(&elevator), before);
        }
    }

    #[tokio::test]
    async fn duplicate_arrival_cannot_also_confirm_boarding() {
        let simulator = ElevatorSimulator::default();
        simulator
            .call(Request::new(CallElevatorRequest { pickup_floor: 2 }))
            .await
            .unwrap();
        let request = || {
            Request::new(AdvanceElevatorSimulationRequest {
                expected_state: "CALLING".into(),
            })
        };
        let (first, second) = tokio::join!(
            simulator.advance_simulation(request()),
            simulator.advance_simulation(request()),
        );
        let outcomes = [first, second];
        assert_eq!(outcomes.iter().filter(|result| result.is_ok()).count(), 1);
        let rejected = outcomes
            .iter()
            .find_map(|result| result.as_ref().err())
            .unwrap();
        assert_eq!(rejected.code(), Code::FailedPrecondition);
        let final_status = simulator
            .get_status(Request::new(GetElevatorStatusRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(final_status.state, "WAITING_FOR_BOARDING");
        assert!(final_status.doors_open);
        assert!(!final_status.robot_in_cabin);
    }
}
