# Robot–bina entegrasyonu

Amaç; robotların elektronik kapı, asansör, geçiş ışığı ve yangın alarmı gibi bina sistemleriyle ortak senaryolar üzerinden çalışmasını sağlamak. Ürün çekirdeği komutları, cihaz geri bildirimlerini ve robotun görev adımlarını birbirine bağlar.

Web arayüzü operatörün senaryoyu izlemesini ve simülasyonu denemesini sağlar. Kotlin backend REST sınırını ve istek doğrulamasını, Rust servisi gRPC üzerinden robot/cihaz bağlantısı sınırını sağlar. İlk sürümde süreç durumu bellekte tutulur.

## İlk case: asansör çağırma ve kat seçimi

Tek robot, tek asansör ve 0–5 katları. `0` giriş katıdır. Bu case gerçek asansöre veya robota hareket komutu göndermez; asansör geri bildirimleri ve robotun kabine giriş/çıkış olayları elle simüle edilir. Asansör case'i mevcut robotun `ROBOT_MODE` ayarından bağımsızdır ve daima `simulation` olarak etiketlenir.

| Durum | Kabul edilen işlem | Sonuç |
| --- | --- | --- |
| `IDLE` / `COMPLETED` | Bulunulan kat için çağır | `CALLING` |
| `CALLING` | Asansörün varışını ve kapı açılışını simüle et | `WAITING_FOR_BOARDING` |
| `WAITING_FOR_BOARDING` | Robotun kabine girişini simüle et | `READY_FOR_DESTINATION` |
| `READY_FOR_DESTINATION` | Farklı bir hedef kat seç | `MOVING` |
| `MOVING` | Hedefe varışı ve kapı açılışını simüle et | `WAITING_FOR_EXIT` |
| `WAITING_FOR_EXIT` | Robotun kabinden çıkışını simüle et | `COMPLETED` |

Kabine giriş doğrulanmadan hedef kat seçilemez. Hareket durumunda kapılar kapalıdır ve robot kabindedir. Hedefe varış ve kapı açılışı doğrulanmadan çıkış tamamlanamaz. Yanlış sıradaki işlemler `409`, geçersiz kat/istekler `400` döndürür; mevcut durum değişmez. Çalışan bir geçiş varken ikinci çağrı kabul edilmez.

Bu geri bildirimler gerçek cihaz adaptörü eklenene kadar yalnızca simülasyon olaylarıdır. Gerçek adaptörde “çağrı kabul edildi” yanıtı, asansörün geldiğini veya kapının açıldığını doğrulamaz. Varış, kapı, kabin ve kat bilgileri ilgili cihaz/robot geri bildirimlerinden alınmalıdır.

### REST uçları

| İstek | Gövde | Amaç |
| --- | --- | --- |
| `GET /api/elevator/status` | — | Asansör ve case durumu |
| `POST /api/elevator/call` | `{ "pickupFloor": 0 }` | Asansörü çağır |
| `POST /api/elevator/select-floor` | `{ "destinationFloor": 2 }` | Hedef katı seç |
| `POST /api/elevator/simulation/advance` | `{ "expectedState": "CALLING" }` | Gösterilen duruma ait bir sonraki geri bildirimi simüle et |

Simülasyon olayı gösterilen `expectedState` ile birlikte gönderilir. Sunucunun durumu farklıysa istek `409` ile reddedilir; aynı adım arka arkaya iki kez ilerletilemez. Eksik veya bilinmeyen durum `400` döndürür. Yolculuk/olay kimliği henüz yoktur; farklı yolculuklar arasındaki tekrar gönderimler için kalıcı idempotency bu sürümün dışındadır.

gRPC sözleşmesi `proto/robot.proto` içindeki `ElevatorService`'tir. Gerçek asansör markası ve arayüzü belirlendiğinde, simülasyonun cihaz tarafına üretici adaptörü eklenir. REST kat seçimi sözleşmesi operatör/robot iş akışını temsil eder; üreticinin protokolü bu sınırın arkasında kalır.

### Bu case'in sınırları

- Robotun kabin içinde gerçekten konumlandığı doğrulanmaz; giriş/çıkış olayları simülasyondur.
- ROS navigasyonu, fiziksel hareket ve gerçek bina ağı bağlantısı yoktur.
- Cihaz bağlantı kaybı, kapı engeli ve zaman aşımı henüz eklenmemiştir. Yangın alarmı aşağıdaki ortak iptal kuralını uygular.
- Çoklu robot kuyruğu, asansör rezervasyonu, kalıcı olay kaydı ve tekrar başlatma sonrası kurtarma sonraki adımlardır.

## Case 02: elektronik kapı geçişi

Tek robot ve tek kapı (`door-01`). Bu case de `ROBOT_MODE` ayarından bağımsız bir simülasyondur. Kapının açılması, robotun eşikten çıkması ve kapının kapanması operatör tarafından simüle edilen geri bildirimlerdir. Fiziksel kapı veya ROS hareket komutu gönderilmez.

| Durum | Kabul edilen işlem | Sonuç |
| --- | --- | --- |
| `IDLE` / `COMPLETED` | Geçiş iste | `OPENING` — kapı henüz kapalı, geçiş izni yok |
| `OPENING` | Kapının açıldığını simüle et | `READY_TO_PASS` — kapı açık, bu geçiş için izin var |
| `READY_TO_PASS` | Geçişi başlat | `PASSING` — robot kapı eşiğinde, kapı açık |
| `PASSING` | Robotun geçişini bitirdiğini simüle et | `CLOSING` — eşik boş, izin kaldırıldı; kapanış bekleniyor |
| `CLOSING` | Kapının kapandığını simüle et | `COMPLETED` — kapı kapalı, eşik boş |

Açılma isteğinin kabulü kapının açık olduğu anlamına gelmez; `READY_TO_PASS` geri bildirimi gelmeden geçiş başlatılamaz. Robotun eşikten çıktığı doğrulanmadan kapanış aşamasına geçilmez. `CLOSING` sırasında kapı hâlâ açık görünür; ancak kapanış geri bildiriminden sonra kapalı kabul edilir. Geçiş sürerken ikinci bir talep reddedilir.

### Kapı REST uçları

| İstek | Gövde | Amaç |
| --- | --- | --- |
| `GET /api/door/status` | — | Kapı, izin ve robotun eşik durumunu oku |
| `POST /api/door/request-passage` | — | Kapının açılması ve geçiş için talep oluştur |
| `POST /api/door/start-passage` | — | Açık kapı doğrulamasından sonra geçişi başlat |
| `POST /api/door/simulation/advance` | `{ "expectedState": "OPENING" }` | Beklenen kapı/robot geri bildirimini simüle et |

gRPC sözleşmesi ortak proto içindeki `DoorService`'tir. Yanlış sıra, devam eden geçişe ikinci talep ve güncel durumla eşleşmeyen `expectedState` için `409`; eksik veya bilinmeyen `expectedState` için `400` döner. Hatalı işlemler durumu değiştirmez. `READY_TO_PASS` aşamasında simülasyon ilerletme yerine açıkça `start-passage` kullanılmalıdır.

### Kapı case'inin sınırları

- Kapı sensörü ve robot konumu gerçek cihazlardan okunmaz; arayüzdeki bütün kapı olayları simülasyondur.
- Kapı engeli, açılma zaman aşımı ve gerçek cihaz adaptörü henüz yoktur. Yangın alarmı aşağıdaki ortak iptal kuralını uygular.
- Tek geçiş durumu bellektedir. Yeniden başlatma durumu sıfırlar; kalıcı olay kimliği ve farklı geçişler arasında tekrar gönderim koruması henüz yoktur.
- Asansör ve kapı ayrı geçiş akışlarıdır; ortak alarm ikisine de uygulanır. Bu sürüm ikisini bir robot rotasında birleştirmez.

## Case 03: yangın alarmında geçişlerin iptali

Alarm, kapı ve asansör simülasyonlarının paylaştığı bir kuraldır. Tek alarm (`fire-alarm-01`) simüle edilir; gerçek yangın paneline, bina kontrolcülerine veya ROS hareketine komut gönderilmez. Bu case yalnızca robot–bina iş akışlarını iptal eder.

| İşlem | Sonuç |
| --- | --- |
| Alarmı simüle et | Alarm aktif olur; devam eden kapı/asansör akışları `CANCELLED` olur |
| Alarm aktifken normal işlem gönder | Yeni çağrı, geçiş veya simülasyon ilerletme `409` ile reddedilir |
| Alarmı temizle | Alarm pasif olur; iptal edilmiş akışlar iptal edilmiş kalır |
| İptal edilen simülasyonları sıfırla | Yalnızca alarm pasifken, yalnızca iptal edilmiş sanal akışlar `IDLE` olur |

Boştaki (`IDLE`) ve tamamlanmış (`COMPLETED`) case'ler iptal edilmiş sayılmaz; buna rağmen alarm aktifken yeni işlem başlatamazlar. Devam eden akış iptal olunca son gözlenen kat, kapı ve robot konumu korunur. Örneğin robot kapı eşiğindeyse iptalden sonra da eşikte görünür; asansör kabinindeyse çıkmış gibi gösterilmez. Kapı geçiş izni kaldırılır. Bu bilgiler simülasyonda son durumun korunmasıdır; fiziksel cihazların durduğuna ilişkin bir doğrulama değildir.

Alarm aktivasyonu ve normal kapı/asansör komutları aynı süreçte ortak bir kilitle sıralanır. Aktivasyon tamamlandıktan sonra normal komutlar case durumunu ilerletemez. Durum okumaları ve mevcut robotun manuel durdurma ucu kullanılabilir kalır.

**Alarmı temizlemek bir devam komutu değildir.** İptal edilen case'in `recoveryRequired` alanı açık kalır ve normal komutları reddeder. Açıkça **İptal edilen simülasyonları sıfırla** seçildiğinde sanal kapı kapalı/eşik boş; sanal asansör kapıları kapalı/kabin boş kabul edilir. Asansörün son bilinen katı korunur; eski çağrı ve hedef kat temizlenir. Bu işlem fiziksel bir kurtarma veya tahliye işlemi değildir. İptal edilmemiş başka bir akış sıfırlanmaz.

### Yangın alarmı REST uçları

| İstek | Gövde | Amaç |
| --- | --- | --- |
| `GET /api/fire-alarm/status` | — | Alarm ve iptal/kurtarma gereksinimini oku |
| `POST /api/fire-alarm/simulation/activate` | — | Alarmı simüle et ve devam eden akışları iptal et |
| `POST /api/fire-alarm/simulation/clear` | — | Alarmı pasife al; iptalleri koru |
| `POST /api/fire-alarm/simulation/reset-cancelled` | — | İptal edilmiş sanal akışları yeniden denemeye hazırla |

Ortak proto içindeki `FireAlarmService` kullanılır. Alarm durumu `active`, `recoveryRequired`, `doorCancelled` ve `elevatorCancelled` alanlarını içerir. Kapı/asansör yanıtlarına da `fireAlarmActive` ve o case'e ait `recoveryRequired` eklenmiştir. Alarm aktifken sıfırlama `409` döner; tekrarlanan aktivasyon/temizleme idempotenttir.

### Alarm case'inin sınırları

- Kapı açma/kapama, asansörün yangın işletimi, robotu fiziksel durdurma ve tahliye rotası içermez.
- Alarm ve iptaller bellektedir; servis yeniden başlayınca simülasyon başlangıç durumuna döner. Gerçek panelden durum doğrulaması, kalıcı kayıt ve birden fazla servis örneği arasında koordinasyon henüz yoktur.
- Harici alarm kaynağı, olay kimliği/sıralaması, yetkili alarm onayı ve fiziksel kurtarma geri bildirimleri gerçek adaptör aşamasına aittir.

## Sonraki case'ler

| Case | İlk uygulanacak davranış | Gerekli geri bildirim |
| --- | --- | --- |
| Geçiş ışığı | Ortak bir geçiş noktasında robot için izin/bekle akışı | Geçişin doluluğu ve kontrolcü izin durumu |

“Traffic light” entegrasyonunda hangi fiziksel kontrolcü veya saha senaryosunun hedeflendiği ayrıca netleştirilecek. Yangın senaryosunda bina cihazlarının yangın işletimi kendi kontrolcülerinde kalır; bu uygulama robot iş akışının bina olaylarına tepkisini yönetir.
