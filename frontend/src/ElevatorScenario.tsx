import { useEffect, useState } from "react";
import type { ElevatorStatus } from "./api";
import { useScenario } from "./useScenario";

const floors = [0, 1, 2, 3, 4, 5];
const floorName = (floor: number) => (floor === 0 ? "0 · Giriş" : `${floor}. kat`);
const stateNames: Record<string, string> = {
  IDLE: "Çağrıya hazır",
  CALLING: "Asansör çağrıldı",
  WAITING_FOR_BOARDING: "Kapı açık, robot bekleniyor",
  READY_FOR_DESTINATION: "Robot kabinde, hedef bekleniyor",
  MOVING: "Hedef kata gidiliyor",
  WAITING_FOR_EXIT: "Hedef katta, çıkış bekleniyor",
  COMPLETED: "Yolculuk tamamlandı",
  CANCELLED: "Yangın alarmı nedeniyle iptal edildi",
};
const advances: Record<string, { label: string; notice: string }> = {
  CALLING: {
    label: "Varışı simüle et",
    notice: "Asansör çağrı katına geldi ve kapı açıldı.",
  },
  WAITING_FOR_BOARDING: {
    label: "Kabine girişi simüle et",
    notice: "Robotun kabine girişi doğrulandı.",
  },
  MOVING: {
    label: "Hedefe varışı simüle et",
    notice: "Asansör hedef kata geldi ve kapı açıldı.",
  },
  WAITING_FOR_EXIT: {
    label: "Çıkışı simüle et",
    notice: "Robot kabinden çıktı. Yolculuk tamamlandı.",
  },
};
const stepNames = ["Çağır", "Kabine gir", "Kat seç", "Yolculuk", "Tamamla"];
const stepIndex: Record<string, number> = {
  IDLE: 0,
  CALLING: 0,
  WAITING_FOR_BOARDING: 1,
  READY_FOR_DESTINATION: 2,
  MOVING: 3,
  WAITING_FOR_EXIT: 3,
  COMPLETED: 4,
};

export function ElevatorScenario() {
  const { status, connection, pollError, commandError, notice, pending, command, refresh } =
    useScenario<ElevatorStatus>("/api/elevator/status", "Asansör");
  const [pickupFloor, setPickupFloor] = useState(0);
  const [destinationFloor, setDestinationFloor] = useState(1);

  useEffect(() => {
    if (status?.pickupFloor != null && destinationFloor === status.pickupFloor) {
      setDestinationFloor(status.pickupFloor === 0 ? 1 : 0);
    }
  }, [status?.pickupFloor, destinationFloor]);

  const online = connection === "online";
  const state = status?.state ?? "";
  const blocked = Boolean(status?.fireAlarmActive || status?.recoveryRequired);
  const canCall = online && !pending && !blocked && (state === "IDLE" || state === "COMPLETED");
  const canSelect = online && !pending && !blocked && state === "READY_FOR_DESTINATION";
  const advance = advances[state];

  return (
    <section className="elevator-section panel" aria-labelledby="elevator-title">
      <div className="elevator-heading">
        <div>
          <p className="eyebrow">ROBOT–BİNA ENTEGRASYONU / CASE 01</p>
          <h2 id="elevator-title">Asansör yolculuğu</h2>
          <p>Çağır, kabine girişi doğrula ve hedef katı seç.</p>
        </div>
        <div className="elevator-badges">
          <span className="mode-badge">Simülasyon</span>
          <span className={`elevator-connection ${online ? "online" : ""}`} role="status">
            <span className="live-dot" />
            {online ? "Asansör bağlı" : connection === "connecting" ? "Asansöre bağlanıyor…" : "Asansör bağlantısı yok"}
          </span>
        </div>
      </div>

      {pollError && <div className="feedback error" role="alert">
        <span>{pollError}{status ? " Son bilinen simülasyon durumu gösteriliyor." : ""}</span>
        <button onClick={refresh} disabled={pending}>Asansör bağlantısını yenile</button>
      </div>}

      <ol className="elevator-steps" aria-label="Asansör senaryosu adımları">
        {stepNames.map((step, index) => <li key={step} className={status && index <= (stepIndex[state] ?? -1) ? "reached" : ""} aria-current={status && index === stepIndex[state] ? "step" : undefined}><span>{index + 1}</span>{step}</li>)}
      </ol>

      <div className={`elevator-status ${online ? "" : "stale"}`}>
        <div><span>Asansör</span><strong>{status?.elevatorId ?? "—"}</strong></div>
        <div><span>Bulunduğu kat</span><strong>{status ? floorName(status.currentFloor) : "—"}</strong></div>
        <div><span>Kapı</span><strong>{status ? status.doorsOpen ? "Açık" : "Kapalı" : "—"}</strong></div>
        <div><span>Robotun konumu</span><strong>{status ? status.robotInCabin ? "Kabinde" : "Kabin dışında" : "—"}</strong></div>
      </div>

      <p className="elevator-state" role="status">{status ? stateNames[state] ?? state : "Asansör verisi bekleniyor"}</p>
      {blocked && <p className="scenario-interlock">{status?.fireAlarmActive
        ? "Yangın alarmı simülasyonu aktif; asansör senaryosu komutları engelleniyor."
        : "Alarm temizlendi; devam etmek için yangın alarmı panelinden iptal edilen simülasyonları sıfırla."} Son kat, kapı ve kabin doluluğu korunur.</p>}
      {status && status.pickupFloor != null && <p className="elevator-route">Çağrı: {floorName(status.pickupFloor)}<span>→</span>Hedef: {status.destinationFloor == null ? "Henüz seçilmedi" : floorName(status.destinationFloor)}</p>}

      <div className="elevator-controls">
        <div className="elevator-control">
          <label htmlFor="pickup-floor">Çağrı katı</label>
          <select id="pickup-floor" value={pickupFloor} onChange={event => setPickupFloor(Number(event.target.value))} disabled={!canCall}>
            {floors.map(floor => <option key={floor} value={floor}>{floorName(floor)}</option>)}
          </select>
          <button className="elevator-button" disabled={!canCall} onClick={() => void command("/api/elevator/call", "Asansör çağrısı alındı.", { pickupFloor })}>Asansörü çağır</button>
        </div>
        <div className="elevator-control">
          <label htmlFor="destination-floor">Hedef kat</label>
          <select id="destination-floor" value={destinationFloor} onChange={event => setDestinationFloor(Number(event.target.value))} disabled={!canSelect}>
            {floors.filter(floor => floor !== status?.pickupFloor).map(floor => <option key={floor} value={floor}>{floorName(floor)}</option>)}
          </select>
          <button className="elevator-button" disabled={!canSelect || destinationFloor === status?.pickupFloor} onClick={() => void command("/api/elevator/select-floor", "Hedef kat seçildi. Kapı kapandı ve yolculuk başladı.", { destinationFloor })}>Hedef katı seç</button>
        </div>
        <div className="elevator-advance">
          <span>Simüle edilmiş sensör olayı</span>
          <button className="elevator-button primary" disabled={!online || pending || blocked || !advance} onClick={() => advance && void command("/api/elevator/simulation/advance", advance.notice, { expectedState: state })}>{pending ? "Gönderiliyor…" : advance?.label ?? "Olay bekleniyor"}</button>
          <p>Kat seçimi, açık kapı ve kabine giriş doğrulandıktan sonra açılır.</p>
        </div>
      </div>

      <div className="elevator-feedback" role="status">{notice && !blocked && <span className="success">{notice}</span>}</div>
      {commandError && <p className="elevator-command-error" role="alert">{commandError}</p>}
      <p className="elevator-note">Bu case tamamen simülasyondur. Gerçek asansöre veya robota hareket komutu göndermez; varış, kapı ve kabin olaylarını sen ilerletirsin.</p>
    </section>
  );
}
