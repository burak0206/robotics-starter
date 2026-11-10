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
- Cihaz bağlantı kaybı, kapı engeli, zaman aşımı ve görev iptali henüz bu case'e eklenmemiştir.
- Çoklu robot kuyruğu, asansör rezervasyonu, kalıcı olay kaydı ve tekrar başlatma sonrası kurtarma sonraki adımlardır.

## Sonraki case'ler

| Case | İlk uygulanacak davranış | Gerekli geri bildirim |
| --- | --- | --- |
| Elektronik kapı | Geçiş talebi → açılma doğrulaması → geçiş → çıkış | Kapı açık/kapalı, robot geçişi tamamladı |
| Geçiş ışığı | Ortak bir geçiş noktasında robot için izin/bekle akışı | Geçişin doluluğu ve kontrolcü izin durumu |
| Yangın alarmı | Alarm olayını robot görevlerine yansıt; normal bina geçişlerini iptal et | Alarm aktif/pasif, robot görev iptali sonucu |

“Traffic light” entegrasyonunda hangi fiziksel kontrolcü veya saha senaryosunun hedeflendiği ayrıca netleştirilecek. Yangın senaryosunda bina cihazlarının yangın işletimi kendi kontrolcülerinde kalır; bu uygulama robot iş akışının bina olaylarına tepkisini yönetir.
