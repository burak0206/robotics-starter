use std::{ffi::OsString, process::Stdio, time::Duration};

use tokio::{process::Command, time::timeout};

/// The ROS CLI is kept at the edge so the default simulator needs no ROS install.
/// A successful publish does not establish whether a physical robot stopped.
#[derive(Clone)]
pub struct Ros2Adapter {
    executable: OsString,
    topic: String,
    deadline: Duration,
}

#[derive(Debug, PartialEq)]
pub enum PublishError {
    Unavailable,
    Failed,
    TimedOut,
}

impl Ros2Adapter {
    pub fn new(topic: String) -> Self {
        Self {
            executable: "ros2".into(),
            topic,
            deadline: Duration::from_secs(3),
        }
    }

    pub async fn stop(&self) -> Result<(), PublishError> {
        // No shell expansion. The topic is always a separate process argument.
        let mut command = Command::new(&self.executable);
        command
            .args([
                "topic",
                "pub",
                "--once",
                "--wait-matching-subscriptions",
                "0",
                &self.topic,
                "geometry_msgs/msg/Twist",
                "{linear: {x: 0.0, y: 0.0, z: 0.0}, angular: {x: 0.0, y: 0.0, z: 0.0}}",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true);

        match timeout(self.deadline, command.status()).await {
            Ok(Ok(status)) if status.success() => Ok(()),
            Ok(Ok(_)) => Err(PublishError::Failed),
            Ok(Err(_)) => Err(PublishError::Unavailable),
            Err(_) => Err(PublishError::TimedOut),
        }
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::{fs, os::unix::fs::PermissionsExt};

    fn fake_ros(script: &str) -> (tempfile::TempDir, Ros2Adapter) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("ros2");
        fs::write(&path, format!("#!/bin/sh\n{script}\n")).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        let adapter = Ros2Adapter {
            executable: path.into_os_string(),
            topic: "/cmd_vel".into(),
            deadline: Duration::from_millis(100),
        };
        (dir, adapter)
    }

    #[tokio::test]
    async fn publish_reports_success_only_when_cli_exits_successfully() {
        let (_dir, adapter) = fake_ros("exit 0");
        assert_eq!(adapter.stop().await, Ok(()));
        let (_dir, adapter) = fake_ros("exit 1");
        assert_eq!(adapter.stop().await, Err(PublishError::Failed));
    }

    #[tokio::test]
    async fn hung_ros_cli_is_bounded() {
        // exec replaces the fake CLI process so no orphaned shell child remains.
        let (_dir, adapter) = fake_ros("exec sleep 10");
        assert_eq!(adapter.stop().await, Err(PublishError::TimedOut));
    }

    #[tokio::test]
    async fn missing_ros_installation_is_an_error() {
        let (_dir, mut adapter) = fake_ros("exit 0");
        adapter.executable = "/not-installed/ros2".into();
        assert_eq!(adapter.stop().await, Err(PublishError::Unavailable));
    }
}
