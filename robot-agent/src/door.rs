//! Independent, in-memory passage simulator. No physical door or robot is operated.

use std::{sync::Arc, time::SystemTime};

use tokio::sync::Mutex;
use tonic::{Request, Response, Status};

use crate::proto::{
    door_service_server::DoorService, AdvanceDoorSimulationRequest, DoorStatus,
    GetDoorStatusRequest, RequestDoorPassageRequest, StartDoorPassageRequest,
};

#[derive(Debug)]
enum DoorError {
    Busy,
    DoorNotReady,
    InvalidExpectedState,
    StaleExpectedState,
    NoSimulationStep,
}

impl From<DoorError> for Status {
    fn from(error: DoorError) -> Self {
        match error {
            DoorError::Busy => Self::failed_precondition("Kapıda devam eden bir geçiş var."),
            DoorError::DoorNotReady => Self::failed_precondition(
                "Geçişe başlamadan önce kapının açılması doğrulanmalıdır.",
            ),
            DoorError::InvalidExpectedState => Self::invalid_argument(
                "İlerletilecek mevcut kapı simülasyon adımı belirtilmelidir.",
            ),
            DoorError::StaleExpectedState => Self::failed_precondition(
                "Kapı simülasyon adımı değişti. Güncel durumu aldıktan sonra tekrar deneyin.",
            ),
            DoorError::NoSimulationStep => Self::failed_precondition(
                "Bu adımda kapı simülasyonu ilerletilemez; önce uygun komutu gönderin.",
            ),
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
enum Phase {
    #[default]
    Idle,
    Opening,
    ReadyToPass,
    Passing,
    Closing,
    Completed,
}

impl Phase {
    fn parse(name: &str) -> Option<Self> {
        match name {
            "IDLE" => Some(Self::Idle),
            "OPENING" => Some(Self::Opening),
            "READY_TO_PASS" => Some(Self::ReadyToPass),
            "PASSING" => Some(Self::Passing),
            "CLOSING" => Some(Self::Closing),
            "COMPLETED" => Some(Self::Completed),
            _ => None,
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Idle => "IDLE",
            Self::Opening => "OPENING",
            Self::ReadyToPass => "READY_TO_PASS",
            Self::Passing => "PASSING",
            Self::Closing => "CLOSING",
            Self::Completed => "COMPLETED",
        }
    }
}

#[derive(Default)]
struct Door {
    phase: Phase,
}

impl Door {
    fn snapshot(&self) -> DoorStatus {
        DoorStatus {
            door_id: "door-01".into(),
            mode: "simulation".into(),
            state: self.phase.name().into(),
            door_open: matches!(
                self.phase,
                Phase::ReadyToPass | Phase::Passing | Phase::Closing
            ),
            passage_allowed: matches!(self.phase, Phase::ReadyToPass | Phase::Passing),
            robot_in_doorway: self.phase == Phase::Passing,
            observed_at_unix_ms: SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64,
        }
    }

    fn request_passage(&mut self) -> Result<(), DoorError> {
        if !matches!(self.phase, Phase::Idle | Phase::Completed) {
            return Err(DoorError::Busy);
        }
        self.phase = Phase::Opening;
        Ok(())
    }

    fn start_passage(&mut self) -> Result<(), DoorError> {
        if self.phase != Phase::ReadyToPass {
            return Err(DoorError::DoorNotReady);
        }
        self.phase = Phase::Passing;
        Ok(())
    }

    fn advance(&mut self, expected_state: &str) -> Result<(), DoorError> {
        let expected = Phase::parse(expected_state).ok_or(DoorError::InvalidExpectedState)?;
        if self.phase != expected {
            return Err(DoorError::StaleExpectedState);
        }
        self.phase = match self.phase {
            Phase::Opening => Phase::ReadyToPass,
            // This event confirms the simulated robot has cleared the doorway;
            // the door remains open until a separate closure feedback event.
            Phase::Passing => Phase::Closing,
            Phase::Closing => Phase::Completed,
            _ => return Err(DoorError::NoSimulationStep),
        };
        Ok(())
    }
}

#[derive(Clone, Default)]
pub struct DoorSimulator {
    door: Arc<Mutex<Door>>,
}

#[tonic::async_trait]
impl DoorService for DoorSimulator {
    async fn get_status(
        &self,
        _request: Request<GetDoorStatusRequest>,
    ) -> Result<Response<DoorStatus>, Status> {
        Ok(Response::new(self.door.lock().await.snapshot()))
    }

    async fn request_passage(
        &self,
        _request: Request<RequestDoorPassageRequest>,
    ) -> Result<Response<DoorStatus>, Status> {
        let mut door = self.door.lock().await;
        door.request_passage()?;
        Ok(Response::new(door.snapshot()))
    }

    async fn start_passage(
        &self,
        _request: Request<StartDoorPassageRequest>,
    ) -> Result<Response<DoorStatus>, Status> {
        let mut door = self.door.lock().await;
        door.start_passage()?;
        Ok(Response::new(door.snapshot()))
    }

    async fn advance_simulation(
        &self,
        request: Request<AdvanceDoorSimulationRequest>,
    ) -> Result<Response<DoorStatus>, Status> {
        let mut door = self.door.lock().await;
        door.advance(&request.into_inner().expected_state)?;
        Ok(Response::new(door.snapshot()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tonic::Code;

    fn error_code(result: Result<(), DoorError>) -> Code {
        Status::from(result.unwrap_err()).code()
    }

    fn semantic_snapshot(door: &Door) -> DoorStatus {
        let mut status = door.snapshot();
        status.observed_at_unix_ms = 0;
        status
    }

    #[test]
    fn passage_requires_opening_then_clearing_before_closure_and_can_be_replayed() {
        let mut door = Door::default();
        let initial = door.snapshot();
        assert_eq!(initial.door_id, "door-01");
        assert_eq!(initial.mode, "simulation");
        assert_eq!(initial.state, "IDLE");
        assert!(!initial.door_open);
        assert!(!initial.passage_allowed);
        assert!(!initial.robot_in_doorway);
        assert!(initial.observed_at_unix_ms > 0);

        for _ in 0..2 {
            door.request_passage().unwrap();
            let opening = door.snapshot();
            assert_eq!(opening.state, "OPENING");
            assert!(!opening.door_open);
            assert!(!opening.passage_allowed);

            door.advance("OPENING").unwrap();
            let ready = door.snapshot();
            assert_eq!(ready.state, "READY_TO_PASS");
            assert!(ready.door_open);
            assert!(ready.passage_allowed);
            assert!(!ready.robot_in_doorway);

            door.start_passage().unwrap();
            let passing = door.snapshot();
            assert_eq!(passing.state, "PASSING");
            assert!(passing.door_open);
            assert!(passing.passage_allowed);
            assert!(passing.robot_in_doorway);

            door.advance("PASSING").unwrap();
            let closing = door.snapshot();
            assert_eq!(closing.state, "CLOSING");
            assert!(closing.door_open);
            assert!(!closing.passage_allowed);
            assert!(!closing.robot_in_doorway);

            door.advance("CLOSING").unwrap();
            let completed = door.snapshot();
            assert_eq!(completed.state, "COMPLETED");
            assert!(!completed.door_open);
            assert!(!completed.passage_allowed);
            assert!(!completed.robot_in_doorway);
        }
    }

    #[test]
    fn out_of_order_commands_do_not_mutate_or_close_an_occupied_doorway() {
        let mut door = Door::default();
        let before = semantic_snapshot(&door);
        assert_eq!(error_code(door.start_passage()), Code::FailedPrecondition);
        assert_eq!(error_code(door.advance("IDLE")), Code::FailedPrecondition);
        assert_eq!(semantic_snapshot(&door), before);

        door.request_passage().unwrap();
        let before = semantic_snapshot(&door);
        assert_eq!(error_code(door.start_passage()), Code::FailedPrecondition);
        assert_eq!(error_code(door.request_passage()), Code::FailedPrecondition);
        assert_eq!(semantic_snapshot(&door), before);

        door.advance("OPENING").unwrap();
        let before = semantic_snapshot(&door);
        assert_eq!(
            error_code(door.advance("READY_TO_PASS")),
            Code::FailedPrecondition
        );
        assert_eq!(error_code(door.request_passage()), Code::FailedPrecondition);
        assert_eq!(semantic_snapshot(&door), before);

        door.start_passage().unwrap();
        let before = semantic_snapshot(&door);
        assert_eq!(error_code(door.start_passage()), Code::FailedPrecondition);
        assert_eq!(error_code(door.request_passage()), Code::FailedPrecondition);
        // A closure event cannot bypass the separate doorway-clear event.
        assert_eq!(
            error_code(door.advance("CLOSING")),
            Code::FailedPrecondition
        );
        assert_eq!(semantic_snapshot(&door), before);
        assert!(door.snapshot().door_open);
        assert!(door.snapshot().robot_in_doorway);

        door.advance("PASSING").unwrap();
        let before = semantic_snapshot(&door);
        assert_eq!(error_code(door.request_passage()), Code::FailedPrecondition);
        assert_eq!(error_code(door.start_passage()), Code::FailedPrecondition);
        assert_eq!(semantic_snapshot(&door), before);

        door.advance("CLOSING").unwrap();
        let before = semantic_snapshot(&door);
        assert_eq!(error_code(door.start_passage()), Code::FailedPrecondition);
        assert_eq!(
            error_code(door.advance("COMPLETED")),
            Code::FailedPrecondition
        );
        assert_eq!(semantic_snapshot(&door), before);
    }

    #[test]
    fn missing_unknown_or_stale_events_never_advance() {
        let mut door = Door::default();
        door.request_passage().unwrap();
        let before = semantic_snapshot(&door);
        for invalid in ["", "UNKNOWN", "opening", "CALLING"] {
            assert_eq!(error_code(door.advance(invalid)), Code::InvalidArgument);
            assert_eq!(semantic_snapshot(&door), before);
        }
        for stale in ["IDLE", "READY_TO_PASS", "PASSING", "CLOSING", "COMPLETED"] {
            assert_eq!(error_code(door.advance(stale)), Code::FailedPrecondition);
            assert_eq!(semantic_snapshot(&door), before);
        }
    }

    #[tokio::test]
    async fn simultaneous_requests_accept_exactly_one() {
        let simulator = DoorSimulator::default();
        let request = || Request::new(RequestDoorPassageRequest {});
        let (first, second) = tokio::join!(
            simulator.request_passage(request()),
            simulator.request_passage(request()),
        );
        let outcomes = [first, second];
        assert_eq!(outcomes.iter().filter(|result| result.is_ok()).count(), 1);
        assert_eq!(
            outcomes
                .iter()
                .find_map(|result| result.as_ref().err())
                .unwrap()
                .code(),
            Code::FailedPrecondition
        );
        let final_status = simulator
            .get_status(Request::new(GetDoorStatusRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(final_status.state, "OPENING");
        assert!(!final_status.passage_allowed);
    }

    #[tokio::test]
    async fn duplicate_opening_feedback_cannot_also_start_passage() {
        let simulator = DoorSimulator::default();
        simulator
            .request_passage(Request::new(RequestDoorPassageRequest {}))
            .await
            .unwrap();
        let event = || {
            Request::new(AdvanceDoorSimulationRequest {
                expected_state: "OPENING".into(),
            })
        };
        let (first, second) = tokio::join!(
            simulator.advance_simulation(event()),
            simulator.advance_simulation(event()),
        );
        let outcomes = [first, second];
        assert_eq!(outcomes.iter().filter(|result| result.is_ok()).count(), 1);
        assert_eq!(
            outcomes
                .iter()
                .find_map(|result| result.as_ref().err())
                .unwrap()
                .code(),
            Code::FailedPrecondition
        );
        let final_status = simulator
            .get_status(Request::new(GetDoorStatusRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(final_status.state, "READY_TO_PASS");
        assert!(final_status.door_open);
        assert!(final_status.passage_allowed);
        assert!(!final_status.robot_in_doorway);
    }

    #[tokio::test]
    async fn duplicate_clearance_feedback_cannot_also_confirm_closed() {
        let simulator = DoorSimulator::default();
        simulator
            .request_passage(Request::new(RequestDoorPassageRequest {}))
            .await
            .unwrap();
        simulator
            .advance_simulation(Request::new(AdvanceDoorSimulationRequest {
                expected_state: "OPENING".into(),
            }))
            .await
            .unwrap();
        simulator
            .start_passage(Request::new(StartDoorPassageRequest {}))
            .await
            .unwrap();
        let event = || {
            Request::new(AdvanceDoorSimulationRequest {
                expected_state: "PASSING".into(),
            })
        };
        let (first, second) = tokio::join!(
            simulator.advance_simulation(event()),
            simulator.advance_simulation(event()),
        );
        let outcomes = [first, second];
        assert_eq!(outcomes.iter().filter(|result| result.is_ok()).count(), 1);
        assert_eq!(
            outcomes
                .iter()
                .find_map(|result| result.as_ref().err())
                .unwrap()
                .code(),
            Code::FailedPrecondition
        );
        let final_status = simulator
            .get_status(Request::new(GetDoorStatusRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(final_status.state, "CLOSING");
        assert!(final_status.door_open);
        assert!(!final_status.robot_in_doorway);
        assert!(!final_status.passage_allowed);
    }
}
