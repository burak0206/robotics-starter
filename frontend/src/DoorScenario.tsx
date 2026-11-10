import type { DoorStatus } from "./api";
import { useScenario } from "./useScenario";

const stateNames: Record<string, string> = {
  IDLE: "Geçiş isteğine hazır",
  OPENING: "Kapı açılışı bekleniyor",
  READY_TO_PASS: "Kapı açık, geçişe izin verildi",
  PASSING: "Robot kapıdan geçiyor",
  CLOSING: "Robot çıktı, kapı kapanışı bekleniyor",
  COMPLETED: "Kapı geçişi tamamlandı",
  CANCELLED: "Yangın alarmı nedeniyle iptal edildi",
};
const advances: Record<string, { label: string; notice: string }> = {
  OPENING: {
    label: "Kapı açılışını simüle et",
    notice: "Kapı açılışı doğrulandı. Geçiş izni verildi.",
  },
  PASSING: {
    label: "Geçişin bitişini simüle et",
    notice: "Robot kapı eşiğinden çıktı. Geçiş izni kaldırıldı.",
  },
  CLOSING: {
    label: "Kapı kapanışını simüle et",
    notice: "Kapının kapandığı doğrulandı. Geçiş tamamlandı.",
  },
};
const steps = ["Kapıyı aç", "Geçiş", "Kapıyı kapat", "Tamamla"];
const stepIndex: Record<string, number> = {
  IDLE: 0, OPENING: 0, READY_TO_PASS: 1, PASSING: 1, CLOSING: 2, COMPLETED: 3,
};

export function DoorScenario() {
  const { status, connection, pollError, commandError, notice, pending, command, refresh } =
    useScenario<DoorStatus>("/api/door/status", "Kapı");
  const online = connection === "online";
  const state = status?.state ?? "";
  const blocked = Boolean(status?.fireAlarmActive || status?.recoveryRequired);
  const canRequest = online && !pending && !blocked && (state === "IDLE" || state === "COMPLETED");
  const canStart = online && !pending && !blocked && state === "READY_TO_PASS"
    && status?.doorOpen && status.passageAllowed && !status.robotInDoorway;
  const advance = advances[state];

  return (
    <section className="elevator-section panel" aria-labelledby="door-title">
      <div className="elevator-heading">
        <div>
          <p className="eyebrow">ROBOT–BİNA ENTEGRASYONU / CASE 02</p>
          <h2 id="door-title">Kapı geçişi</h2>
          <p>Açılışı doğrula, geçiş izni ver ve robot çıktıktan sonra kapat.</p>
        </div>
        <div className="elevator-badges">
          <span className="mode-badge">Simülasyon</span>
          <span className={`elevator-connection ${online ? "online" : ""}`} role="status">
            <span className="live-dot" />
            {online ? "Kapı bağlı" : connection === "connecting" ? "Kapıya bağlanıyor…" : "Kapı bağlantısı yok"}
          </span>
        </div>
      </div>

      {pollError && <div className="feedback error" role="alert">
        <span>{pollError}{status ? " Son bilinen simülasyon durumu gösteriliyor." : ""}</span>
        <button onClick={refresh} disabled={pending}>Kapı bağlantısını yenile</button>
      </div>}

      <ol className="elevator-steps door-steps" aria-label="Kapı senaryosu adımları">
        {steps.map((step, index) => <li key={step} className={status && index <= (stepIndex[state] ?? -1) ? "reached" : ""} aria-current={status && index === stepIndex[state] ? "step" : undefined}><span>{index + 1}</span>{step}</li>)}
      </ol>

      <div className={`elevator-status ${online ? "" : "stale"}`}>
        <div><span>Kapı kimliği</span><strong>{status?.doorId ?? "—"}</strong></div>
        <div><span>Kapı sensörü</span><strong>{status ? status.doorOpen ? "Açık" : "Kapalı" : "—"}</strong></div>
        <div><span>Geçiş izni</span><strong>{status ? status.passageAllowed ? "İzin verildi" : "İzin yok" : "—"}</strong></div>
        <div><span>Robotun konumu</span><strong>{status ? status.robotInDoorway ? "Kapı eşiğinde" : "Kapı eşiği dışında" : "—"}</strong></div>
      </div>

      <p className="elevator-state" role="status">{status ? stateNames[state] ?? state : "Kapı verisi bekleniyor"}</p>
      {blocked && <p className="scenario-interlock">{status?.fireAlarmActive
        ? "Yangın alarmı simülasyonu aktif; kapı senaryosu komutları engelleniyor."
        : "Alarm temizlendi; devam etmek için yangın alarmı panelinden iptal edilen simülasyonları sıfırla."} Son kapı ve robot konumu korunur.</p>}
      <div className="elevator-controls door-controls">
        <div className="elevator-control">
          <span className="door-control-label">1. Erişim isteği</span>
          <button className="elevator-button" disabled={!canRequest} onClick={() => void command("/api/door/request-passage", "Geçiş isteği alındı. Kapının açılması bekleniyor.")}>Geçiş iste</button>
          <p>Kapı açılışı doğrulanana kadar geçiş izni verilmez.</p>
        </div>
        <div className="elevator-control">
          <span className="door-control-label">2. Robotun geçişi</span>
          <button className="elevator-button" disabled={!canStart} onClick={() => void command("/api/door/start-passage", "Robotun kapı eşiğinden geçişi başladı.")}>Geçişi başlat</button>
          <p>Geçiş bitişi doğrulanmadan kapanış aşamasına geçilmez.</p>
        </div>
        <div className="elevator-advance">
          <span>Simüle edilmiş sensör olayı</span>
          <button className="elevator-button primary" disabled={!online || pending || blocked || !advance} onClick={() => advance && void command("/api/door/simulation/advance", advance.notice, { expectedState: state })}>{pending ? "Gönderiliyor…" : advance?.label ?? "Olay bekleniyor"}</button>
          <p>Açılış, robotun çıkışı ve kapanış sensörlerini sırayla doğrula.</p>
        </div>
      </div>

      <div className="elevator-feedback" role="status">{notice && !blocked && <span className="success">{notice}</span>}</div>
      {commandError && <p className="elevator-command-error" role="alert">{commandError}</p>}
      <p className="elevator-note">Bu case tamamen simülasyondur. Gerçek kapıya veya robota hareket komutu göndermez; açılış, geçiş ve kapanış sensör olaylarını sen ilerletirsin.</p>
    </section>
  );
}
