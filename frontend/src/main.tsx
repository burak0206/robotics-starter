import { StrictMode, useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { request, type RobotStatus, type StopResponse } from "./api";
import { ElevatorScenario } from "./ElevatorScenario";
import "./style.css";

type Connection = "connecting" | "online" | "offline";
const stateNames: Record<string, string> = {
  IDLE: "Hazır",
  STOPPED: "Durduruldu",
  STOP_REQUESTED: "Dur komutu gönderildi",
  UNKNOWN: "Bilinmiyor",
};

function Icon({
  kind,
  size = 20,
}: {
  kind: "robot" | "battery" | "speed" | "arrow" | "signal";
  size?: number;
}) {
  const paths = {
    robot: (
      <>
        <rect x="5" y="7" width="14" height="13" rx="4" />
        <path d="M12 3v4M9 13h.01M15 13h.01M9 17h6M2 11v5M22 11v5" />
      </>
    ),
    battery: (
      <>
        <rect x="3" y="6" width="16" height="12" rx="2" />
        <path d="M22 10v4M7 10v4M11 10v4M15 10v4" />
      </>
    ),
    speed: (
      <>
        <path d="M4 18a9 9 0 1 1 16 0M12 13l5-5M5 12h2M12 5v2M17 17h2" />
        <circle cx="12" cy="14" r="2" />
      </>
    ),
    arrow: (
      <>
        <path d="M5 12h14M13 6l6 6-6 6" />
      </>
    ),
    signal: (
      <>
        <path d="M4 19v-4M9 19v-8M14 19V7M19 19V3" />
      </>
    ),
  };
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[kind]}
    </svg>
  );
}

function App() {
  const [status, setStatus] = useState<RobotStatus | null>(null);
  const [connection, setConnection] = useState<Connection>("connecting");
  const [error, setError] = useState<string | null>(null);
  const [commandError, setCommandError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [stopping, setStopping] = useState(false);
  const [retry, setRetry] = useState(0);
  const stoppingRef = useRef(false);
  const generation = useRef(0);
  const pollController = useRef<AbortController | null>(null);
  const stopController = useRef<AbortController | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      stopController.current?.abort();
    };
  }, []);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      if (!active) return;
      if (stoppingRef.current) {
        timer = setTimeout(poll, 2000);
        return;
      }
      const epoch = generation.current;
      const controller = new AbortController();
      pollController.current = controller;
      const timeout = setTimeout(() => controller.abort(), 4000);
      try {
        const result = await request<RobotStatus>(
          "/api/robot/status",
          controller.signal,
        );
        if (active && epoch === generation.current) {
          setStatus(result);
          setConnection("online");
          setError(null);
        }
      } catch (cause) {
        if (active && epoch === generation.current) {
          setConnection("offline");
          setError(
            controller.signal.aborted
              ? "Robot bağlantısı zaman aşımına uğradı."
              : (cause as Error).message,
          );
        }
      } finally {
        clearTimeout(timeout);
        if (pollController.current === controller)
          pollController.current = null;
        if (active) timer = setTimeout(poll, 2000);
      }
    }
    void poll();
    return () => {
      active = false;
      clearTimeout(timer);
      pollController.current?.abort();
    };
  }, [retry]);

  async function stop() {
    if (stoppingRef.current || connection !== "online") return;
    stoppingRef.current = true;
    setStopping(true);
    setNotice(null);
    setCommandError(null);
    generation.current += 1;
    pollController.current?.abort();
    const controller = new AbortController();
    stopController.current = controller;
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const result = await request<StopResponse>(
        "/api/robot/stop",
        controller.signal,
        "POST",
      );
      if (!mounted.current) return;
      setStatus(result.status);
      setConnection("online");
      if (result.accepted)
        setNotice(result.message || "Durdurma komutu kabul edildi.");
      else setCommandError(result.message || "Durdurma komutu kabul edilmedi.");
    } catch (cause) {
      if (!mounted.current) return;
      setCommandError(
        controller.signal.aborted
          ? "Durdurma isteği zaman aşımına uğradı. Komutun sonucunu doğrulayın."
          : (cause as Error).message,
      );
      setConnection("offline");
    } finally {
      clearTimeout(timeout);
      stoppingRef.current = false;
      if (mounted.current) setStopping(false);
    }
  }

  const connected = connection === "online";
  const state = status
    ? (stateNames[status.state] ?? status.state)
    : "Bekleniyor";
  const mode =
    status?.mode === "simulation"
      ? "Simülasyon"
      : status?.mode === "ros2"
        ? "ROS 2"
        : "Bağlantı bekleniyor";

  return (
    <div className="app">
      <header className="topbar">
        <a className="brand" href="/" aria-label="Rover ana sayfa">
          <span className="brand-mark">
            <Icon kind="robot" size={23} />
          </span>
          rover<span className="brand-dot">.</span>
        </a>
        <span className="nav-label">
          <span className="nav-dot" /> Kontrol paneli
        </span>
        <span className="version">
          PROTOTİP <span>v0.1</span>
        </span>
      </header>

      <main>
        <div className="page-heading">
          <div>
            <p className="eyebrow">ROBOT KONTROL MERKEZİ</p>
            <h1>Robotun, bir bakışta.</h1>
            <p className="subtitle">Durumunu takip et. Gerektiğinde durdur.</p>
          </div>
          <div className={`connection ${connection}`} role="status">
            <span className="live-dot" />
            {connected
              ? "Bağlantı aktif"
              : connection === "connecting"
                ? "Bağlanıyor…"
                : "Bağlantı yok"}
          </div>
        </div>

        {error && (
          <div className="feedback error" role="alert">
            <span>
              {error}
              {status && !connected
                ? " Ekranda son bilinen durum gösteriliyor."
                : ""}
            </span>
            <button
              onClick={() => setRetry((value) => value + 1)}
              disabled={stopping}
            >
              Yeniden dene
            </button>
          </div>
        )}

        <div className="dashboard">
          <section className="robot-card panel" aria-label="Robot bilgileri">
            <div className="card-top">
              <span className="section-label">ROBOT / 01</span>
              <span
                className={`mode-badge ${status?.mode === "ros2" ? "ros" : ""}`}
              >
                {mode}
              </span>
            </div>
            <div
              className={`robot-scene ${connected ? "" : "disconnected"}`}
              aria-hidden="true"
            >
              <div className="radar radar-outer" />
              <div className="radar radar-inner" />
              <div className="axis axis-x" />
              <div className="axis axis-y" />
              <div className="robot-model">
                <span className="wheel left top" />
                <span className="wheel left bottom" />
                <span className="wheel right top" />
                <span className="wheel right bottom" />
                <div className="chassis">
                  <div className="sensor" />
                  <div className="robot-eyes">
                    <i />
                    <i />
                  </div>
                  <span className="chassis-line" />
                  <span className="chassis-label">R·01</span>
                </div>
              </div>
              <span className="scene-coordinate">
                ROVER <span>ÜST GÖRÜNÜM</span>
              </span>
            </div>
            <div className="robot-identity">
              <div>
                <h2>{status?.name ?? "Robot bekleniyor"}</h2>
                <p>{status?.robotId ?? "Henüz veri alınmadı"}</p>
              </div>
              <span
                className={`state-badge ${status?.state === "STOPPED" ? "stopped" : ""} ${!connected ? "muted" : ""}`}
              >
                <span />
                {state}
              </span>
            </div>
            <div className="robot-footnote">
              <Icon kind="signal" size={15} />
              <span>
                {connected
                  ? "Durum her 2 saniyede yenilenir"
                  : "Robot servisine bağlantı bekleniyor"}
              </span>
            </div>
          </section>

          <div className="right-column">
            <div className="metrics">
              <section className="metric panel">
                <div className="metric-title">
                  <span>Batarya</span>
                  <Icon kind="battery" />
                </div>
                <div className="metric-value">
                  {status?.batteryPercent ?? "—"}
                  <span>{status?.batteryPercent != null ? "%" : ""}</span>
                </div>
                <div className="battery-track" aria-hidden="true">
                  <div
                    style={{
                      width: `${Math.max(0, Math.min(100, status?.batteryPercent ?? 0))}%`,
                    }}
                  />
                </div>
                <p>
                  {status?.batteryPercent != null
                    ? "Kalan şarj"
                    : "Telemetri alınmıyor"}
                </p>
              </section>
              <section className="metric panel">
                <div className="metric-title">
                  <span>Doğrusal hız</span>
                  <Icon kind="speed" />
                </div>
                <div className="metric-value">
                  {status?.linearVelocityMps?.toFixed(2) ?? "—"}
                  <span>{status?.linearVelocityMps != null ? "m/s" : ""}</span>
                </div>
                <div className="speed-line" aria-hidden="true">
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                </div>
                <p>
                  {status?.linearVelocityMps != null
                    ? "Anlık hız"
                    : "Telemetri alınmıyor"}
                </p>
              </section>
            </div>

            <section
              className="command-card panel"
              aria-labelledby="command-title"
            >
              <div className="command-heading">
                <span className="section-label">KOMUT</span>
                <span className="command-number">01</span>
              </div>
              <h2 id="command-title">Kontrol sende.</h2>
              <p>Robota sıfır hız komutu gönder.</p>
              <button
                className="stop-button"
                onClick={() => void stop()}
                disabled={!connected || !status || stopping}
              >
                <span className={stopping ? "spinner" : "stop-square"} />
                {stopping ? "Gönderiliyor…" : "Durdur"}
                <Icon kind="arrow" size={19} />
              </button>
              <div className="command-feedback" role="status">
                {notice && <span className="success">{notice}</span>}
              </div>
              {commandError && (
                <p className="command-error" role="alert">
                  {commandError}
                </p>
              )}
              <p className="safety-note">
                Yazılımsal durdurma komutudur. Fiziksel acil durdurma yerine
                geçmez.
              </p>
            </section>
          </div>
        </div>

        <ElevatorScenario />

        <footer>
          <span>
            <span className="footer-dot" /> Tek robot. Küçük bir başlangıç.
          </span>
          <span>
            {status
              ? `Son veri · ${new Date(status.observedAtUnixMs).toLocaleTimeString("tr-TR")}`
              : "İlk veri bekleniyor"}
          </span>
        </footer>
      </main>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
