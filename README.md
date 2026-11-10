# Robot–bina entegrasyonu başlangıç projesi

Robotların kapılar, asansörler ve bina olaylarıyla birlikte çalışacağı senaryolar için küçük bir başlangıç. İlk case **asansör çağırma ve kat seçimi**; mevcut robot durum ekranı ve **durdur** komutu da korunur. Donanım gerektirmeden simülasyonla açılır.

```text
React / TypeScript web
        │ REST / JSON
        ▼
Spring Boot / Kotlin
        │ gRPC / Protobuf
        ▼
Rust / tonic robot-agent
        ├── RobotService → simulation / ros2 (/cmd_vel)
        └── ElevatorService → asansör case simülasyonu
```

## Hızlı başlangıç

Docker ve Docker Compose v2 ile proje kökünde:

```sh
docker compose up --build -d
```

- Web: <http://localhost:5173>
- REST API: <http://localhost:8080/api/robot/status>
- Loglar: `docker compose logs -f`
- Kapatma: `docker compose down`

İlk derlemede bağımlılıklar indirilir. Ekran bağlantı kurulana kadar bekleme durumunu gösterir. gRPC servisi yalnızca Compose ağına açıktır; web ve REST portları yerel makineye bağlanır.

Simülasyon başlangıçta `IDLE`, %84 pil ve 0 m/s hız döndürür. Durdurunca `STOPPED` olur; tekrar durdurmak aynı sonucu verir. Durum bellektedir, Rust servisi yeniden başlayınca sıfırlanır. Pil ve hız simülasyon sabitleridir, gerçek sensör ölçümü değildir.

Web'deki asansör bölümünde giriş katını seçip **Asansörü çağır** ile başla. Varış/kapı açılışı ve kabine girişi simüle ettikten sonra hedef katı seç; varışı ve kabinden çıkışı simüle ederek case'i tamamla. Katlar 0–5 arasındadır. Asansör case'i her zaman simülasyondur ve gerçek robot/asansör hareketi başlatmaz. [Case sözleşmesi ve sonraki entegrasyonlar](docs/use-cases.md).

## Yerel geliştirme

Gereksinimler: Rust 1.93+, JDK 21 ve Node.js 22.12+ (veya 24+). Gradle Wrapper projeye dahildir. Protobuf derleyicisi Rust build bağımlılığı olarak, Java tarafında Gradle tarafından indirilir.

Üç ayrı terminalde proje kökünden:

```sh
# 1. Rust gRPC servisi, 127.0.0.1:50051
cd robot-agent
cargo run --locked
```

```sh
# 2. Kotlin REST API, :8080
cd backend
./gradlew bootRun
```

```sh
# 3. Web geliştirme sunucusu, :5173
cd frontend
npm ci
npm run dev
```

Web'deki `/api` isteklerini Vite yerelde API'ye, Docker'da nginx backend servisine yönlendirir. CORS ayarı gerekmez.

| Değişken | Varsayılan | Kullanıldığı yer |
| --- | --- | --- |
| `GRPC_ADDR` | `127.0.0.1:50051` | Rust dinleme adresi |
| `ROBOT_MODE` | `simulation` | Rust; `simulation` veya `ros2` |
| `ROS_CMD_VEL_TOPIC` | `/cmd_vel` | ROS 2 durdurma konusu |
| `ROBOT_GRPC_HOST` | `localhost` | Kotlin → Rust |
| `ROBOT_GRPC_PORT` | `50051` | Kotlin → Rust |
| `SERVER_PORT` | `8080` | REST API portu |
| `API_PROXY_TARGET` | `http://127.0.0.1:8080` | Vite → REST API |

## API ve ortak sözleşme

Ortak sözleşme: [`proto/robot.proto`](proto/robot.proto). Kotlin ve Rust aynı dosyadan gRPC kodu üretir.

```sh
curl http://localhost:8080/api/robot/status
curl -X POST http://localhost:8080/api/robot/stop
```

`GET /api/robot/status` örneği:

```json
{
  "robotId": "robot-01",
  "name": "Rover 01",
  "mode": "simulation",
  "state": "IDLE",
  "batteryPercent": 84,
  "linearVelocityMps": 0.0,
  "observedAtUnixMs": 1794319200000
}
```

`POST /api/robot/stop`, `{ "accepted": true, "message": "...", "status": { ... } }` döndürür. Rust'a erişilemiyorsa REST `503`, gRPC zaman aşımında `504` ve `{ "code": "...", "message": "..." }` döner. Ekran her iki saniyede durumu yeniler; bağlantı kaybolursa son veriyi güncelmiş gibi sunmaz.

## ROS 2 bağlantısı

Bu ilk adımda ROS 2 entegrasyonu **yalnızca durdurma yayınıdır**. ROS 2 ve robotun çalışma ortamı kurulu, ilgili kurulum/workspace `source` edilmiş bir terminalde:

```sh
source /opt/ros/jazzy/setup.bash
# Gerekirse: source /path/to/robot_ws/install/setup.bash
cd robot-agent
ROBOT_MODE=ros2 ROS_CMD_VEL_TOPIC=/cmd_vel cargo run --locked
```

Rust adaptörü `ros2 topic pub --once --wait-matching-subscriptions 0` ile `geometry_msgs/msg/Twist` mesajının bütün eksenlerini sıfır gönderir. Konuyu robotunun kullandığı adrese göre ayarla; gerekiyorsa ROS domain ve middleware ortamını da aynı terminalde yapılandır. Varsayılan Compose imajı ROS içermez; ROS modu yerel ROS ortamında çalıştırılır.

ROS modunda pil ve hız `null`, ilk durum `UNKNOWN` olur. Yayın başarılıysa `STOP_REQUESTED` gösterilir. Bu, komutun yayınlandığını belirtir; robotun fiziksel olarak durduğuna dair geri bildirim yoktur. `observedAtUnixMs` servis yanıtının zamanıdır, ROS sensör ölçüm zamanı değildir. Yayın başarısız olursa veya zaman aşımına uğrarsa API hata verir. Gerçek ROS/donanım bağlantısı simülasyon testlerinden ayrı doğrulanmalıdır.

**Durdur düğmesi yazılımsal bir komuttur; fiziksel acil durdurma sistemi değildir.** Sıfır hız mesajı, başka bir yayıncı veya robot kontrolcüsü tarafından geçersiz kılınabilir.

## Doğrulama

```sh
cd robot-agent && cargo test --locked
cd ../backend && ./gradlew test --no-daemon
cd ../frontend && npm ci && npm run build
```

Servisler çalışırken proje kökünden uçtan uca simülasyon kontrolü:

```sh
python3 scripts/smoke.py
# Web'in API proxy'si üzerinden de:
python3 scripts/smoke.py --base-url http://localhost:5173
```

Smoke testi gerçek ROS modunda komut göndermez; yalnızca `simulation` yanıtını aldıktan sonra durdurmayı dener.

Asansör case'inin tamamını ve yanlış sıradaki işlemlerin reddini kontrol etmek için:

```sh
python3 scripts/elevator_smoke.py
# Web proxy'si üzerinden:
python3 scripts/elevator_smoke.py --base-url http://localhost:5173
```

Case durumu `IDLE` veya `COMPLETED` olmalıdır; yarım kalmış bir akışı önce arayüzde tamamla. Servis yeniden başlayınca simülasyon durumu sıfırlanır.

## Kapsam

Bu sürümde tek robot, durum/durdurma işlemleri, bağlantı/hata görünümü ve tek asansör için sıralı bir simülasyon var. Elektronik kapı, geçiş ışığı ve yangın alarmı case'leri henüz uygulanmadı. Veritabanı, giriş sistemi, fiziksel hareket, harita ve canlı ROS telemetrisi yok. Dış ağa açmadan önce kimlik doğrulama ve TLS eklenmeli; şimdilik yerel geliştirme içindir.
