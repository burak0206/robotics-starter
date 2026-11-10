mod adapter;
mod door;
mod elevator;

use std::{net::SocketAddr, sync::Arc, time::SystemTime};

use adapter::{PublishError, Ros2Adapter};
use door::DoorSimulator;
use elevator::ElevatorSimulator;
use tokio::sync::Mutex;
use tonic::{transport::Server, Request, Response, Status};
use tracing::{info, warn};

pub mod proto {
    tonic::include_proto!("robotics.v1");
}

use proto::{
    door_service_server::DoorServiceServer,
    elevator_service_server::ElevatorServiceServer,
    robot_service_server::{RobotService, RobotServiceServer},
    GetStatusRequest, RobotStatus, StopRequest, StopResponse,
};

#[derive(Clone)]
enum Mode {
    Simulation,
    Ros2(Ros2Adapter),
}

impl Mode {
    fn parse(value: &str, topic: String) -> Result<Self, String> {
        match value {
            "simulation" => Ok(Self::Simulation),
            "ros2" => Ok(Self::Ros2(Ros2Adapter::new(topic))),
            _ => Err(format!(
                "Invalid ROBOT_MODE '{value}': expected simulation or ros2"
            )),
        }
    }
}

#[derive(Clone)]
struct RobotAgent {
    mode: Mode,
    state: Arc<Mutex<String>>,
    // Keep ROS publishes sequential without blocking status reads.
    stop_gate: Arc<Mutex<()>>,
}

impl RobotAgent {
    fn new(mode: Mode) -> Self {
        let state = match &mode {
            Mode::Simulation => "IDLE",
            Mode::Ros2(_) => "UNKNOWN",
        };
        Self {
            mode,
            state: Arc::new(Mutex::new(state.into())),
            stop_gate: Arc::new(Mutex::new(())),
        }
    }

    async fn snapshot(&self) -> RobotStatus {
        let simulated = matches!(self.mode, Mode::Simulation);
        RobotStatus {
            robot_id: "robot-01".into(),
            name: "Rover 01".into(),
            mode: if simulated { "simulation" } else { "ros2" }.into(),
            state: self.state.lock().await.clone(),
            battery_percent: simulated.then_some(84),
            linear_velocity_mps: simulated.then_some(0.0),
            observed_at_unix_ms: SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64,
        }
    }
}

#[tonic::async_trait]
impl RobotService for RobotAgent {
    async fn get_status(
        &self,
        _request: Request<GetStatusRequest>,
    ) -> Result<Response<RobotStatus>, Status> {
        Ok(Response::new(self.snapshot().await))
    }

    async fn stop(&self, _request: Request<StopRequest>) -> Result<Response<StopResponse>, Status> {
        let _guard = self.stop_gate.lock().await;
        let (state, message) = match &self.mode {
            Mode::Simulation => ("STOPPED", "Simülasyonda robot durduruldu."),
            Mode::Ros2(adapter) => {
                if let Err(error) = adapter.stop().await {
                    warn!(?error, "ROS 2 stop publish failed");
                    return Err(match error {
                        PublishError::TimedOut => {
                            Status::deadline_exceeded("ROS 2 dur komutu zaman aşımına uğradı.")
                        }
                        _ => Status::unavailable("ROS 2 dur komutu yayınlanamadı."),
                    });
                }
                (
                    "STOP_REQUESTED",
                    "ROS 2 sıfır hız komutu yayınlandı; fiziksel duruş doğrulanmadı.",
                )
            }
        };
        *self.state.lock().await = state.into();
        Ok(Response::new(StopResponse {
            accepted: true,
            message: message.into(),
            status: Some(self.snapshot().await),
        }))
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "robot_agent=info".into()),
        )
        .init();
    let addr: SocketAddr = std::env::var("GRPC_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:50051".into())
        .parse()?;
    let mode_name = std::env::var("ROBOT_MODE").unwrap_or_else(|_| "simulation".into());
    let topic = std::env::var("ROS_CMD_VEL_TOPIC").unwrap_or_else(|_| "/cmd_vel".into());
    let agent = RobotAgent::new(Mode::parse(&mode_name, topic)?);
    info!(%addr, mode = %mode_name, "Robot gRPC service listening");
    Server::builder()
        .add_service(RobotServiceServer::new(agent))
        .add_service(ElevatorServiceServer::new(ElevatorSimulator::default()))
        .add_service(DoorServiceServer::new(DoorSimulator::default()))
        .serve_with_shutdown(addr, async {
            if let Err(error) = tokio::signal::ctrl_c().await {
                warn!(%error, "Could not register shutdown signal");
            }
        })
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn simulation_starts_idle_and_stop_is_idempotent() {
        let agent = RobotAgent::new(Mode::Simulation);
        let initial = agent
            .get_status(Request::new(GetStatusRequest {}))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(initial.state, "IDLE");
        assert_eq!(initial.mode, "simulation");
        assert_eq!(initial.battery_percent, Some(84));

        for _ in 0..2 {
            let stopped = agent
                .stop(Request::new(StopRequest {}))
                .await
                .unwrap()
                .into_inner();
            assert!(stopped.accepted);
            let status = stopped.status.unwrap();
            assert_eq!(status.state, "STOPPED");
            assert_eq!(status.linear_velocity_mps, Some(0.0));
            assert!(status.observed_at_unix_ms > 0);
        }
        assert_eq!(agent.snapshot().await.state, "STOPPED");
    }

    #[tokio::test]
    async fn ros_status_does_not_claim_telemetry() {
        let mode = Mode::parse("ros2", "/cmd_vel".into()).unwrap();
        let status = RobotAgent::new(mode).snapshot().await;
        assert_eq!(status.state, "UNKNOWN");
        assert_eq!(status.mode, "ros2");
        assert!(status.battery_percent.is_none());
        assert!(status.linear_velocity_mps.is_none());
    }

    #[test]
    fn invalid_mode_is_rejected_instead_of_silent_simulation() {
        assert!(Mode::parse("simulaton", "/cmd_vel".into()).is_err());
    }
}
