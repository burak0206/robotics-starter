import { useEffect } from "react";
import type { FireAlarmStatus } from "./api";
import { refreshScenarioStatuses, useScenario } from "./useScenario";

export function FireAlarmScenario() {
  const { status, connection, pollError, commandError, notice, pending, command, refresh } =
    useScenario<FireAlarmStatus>("/api/fire-alarm/status", "Yangın alarmı");
  const online = connection === "online";

  useEffect(() => {
    if (status) {
      // Changes observed through polling can come from another operator.
      // Invalidate older case responses for every observed alarm transition.
      refreshScenarioStatuses("/api/elevator/status", "/api/door/status");
    }
  }, [status?.active, status?.recoveryRequired, status?.doorCancelled, status?.elevatorCancelled]);

  const summary = !status
    ? "Alarm durumu bekleniyor"
    : status.active
      ? "Alarm simülasyonu aktif. Yeni kapı ve asansör işlemleri engelleniyor."
      : status.recoveryRequired
        ? "Alarm temizlendi. İptal edilen senaryolar sıfırlama bekliyor."
        : "Alarm simülasyonu pasif. Entegrasyon senaryoları kullanılabilir.";

  return (
    <section className={`elevator-section fire-alarm-section panel ${status?.active ? "fire-alarm-active" : ""}`} aria-labelledby="fire-alarm-title">
      <div className="elevator-heading">
        <div>
          <p className="eyebrow">ROBOT–BİNA ENTEGRASYONU / CASE 03</p>
          <h2 id="fire-alarm-title">Yangın alarmı</h2>
          <p>Alarm olayında devam eden senaryoları iptal et, yeni işlemleri engelle.</p>
        </div>
        <div className="elevator-badges">
          <span className="mode-badge">Simülasyon</span>
          <span className={`elevator-connection ${online ? "online" : ""}`} role="status">
            <span className="live-dot" />
            {online ? "Alarm servisi bağlı" : connection === "connecting" ? "Alarm servisine bağlanıyor…" : "Alarm bağlantısı yok"}
          </span>
        </div>
      </div>

      {pollError && <div className="feedback error" role="alert">
        <span>{pollError}{status ? " Son bilinen simülasyon durumu gösteriliyor." : ""}</span>
        <button onClick={refresh} disabled={pending}>Alarm bağlantısını yenile</button>
      </div>}

      <p className={`fire-alarm-summary ${status?.active ? "active" : ""}`} role={status?.active ? "alert" : "status"}>{summary}</p>
      <div className={`elevator-status ${online ? "" : "stale"}`}>
        <div><span>Alarm</span><strong>{status ? status.active ? "Aktif" : "Pasif" : "—"}</strong></div>
        <div><span>Kapı senaryosu</span><strong>{status ? status.doorCancelled ? "İptal edildi" : "İptal yok" : "—"}</strong></div>
        <div><span>Asansör senaryosu</span><strong>{status ? status.elevatorCancelled ? "İptal edildi" : "İptal yok" : "—"}</strong></div>
        <div><span>Manuel sıfırlama</span><strong>{status ? status.recoveryRequired ? "Gerekli" : "Gerekmiyor" : "—"}</strong></div>
      </div>

      <div className="elevator-controls fire-alarm-controls">
        <div className="elevator-control">
          <span className="door-control-label">1. Alarm olayı</span>
          <button className="elevator-button fire-alarm-button" disabled={!online || pending || !status || status.active} onClick={() => void command("/api/fire-alarm/simulation/activate", "Yangın alarmı simülasyonu etkinleştirildi. Yeni kapı ve asansör komutları engellendi.")}>Yangın alarmını simüle et</button>
        </div>
        <div className="elevator-control">
          <span className="door-control-label">2. Alarmı kaldır</span>
          <button className="elevator-button" disabled={!online || pending || !status?.active} onClick={() => void command("/api/fire-alarm/simulation/clear", "Alarm temizlendi. İptal edilen senaryolar otomatik devam etmez.")}>Alarmı temizle</button>
        </div>
        <div className="elevator-control">
          <span className="door-control-label">3. Simülasyonu yeniden hazırla</span>
          <button className="elevator-button" disabled={!online || pending || !status || status.active || !status.recoveryRequired} onClick={() => void command("/api/fire-alarm/simulation/reset-cancelled", "İptal edilen sanal senaryolar başlangıç durumuna sıfırlandı.")}>İptal edilen simülasyonları sıfırla</button>
        </div>
      </div>

      <div className="elevator-feedback" role="status">{pending ? "Alarm isteği gönderiliyor…" : notice && <span className="success">{notice}</span>}</div>
      {commandError && <p className="elevator-command-error" role="alert">{commandError}</p>}
      <p className="elevator-note">Bu panel yalnızca simülasyondur; gerçek yangın paneline, bina sistemlerine veya ROS üzerinden robota komut göndermez. Alarmın temizlenmesi yolculukları devam ettirmez. Sıfırlama, yalnızca iptal edilen sanal kapı ve robot/kabin konumlarını başlangıç değerlerine alır.</p>
    </section>
  );
}
