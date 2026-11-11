<a id="turkce"></a>

[Türkçe](#turkce) · [English](#english)

# Robot–bina entegrasyonu başlangıç projesi

Robotların kapılar, asansörler ve bina olaylarıyla birlikte çalışacağı senaryolar için küçük bir başlangıç. **Asansör çağırma ve kat seçimi**, **elektronik kapı geçişi** ve **yangın alarmında geçişlerin iptali** case'lerini içerir; mevcut robot durum ekranı ve **durdur** komutu da korunur. Donanım gerektirmeden simülasyonla açılır.

**Ürün ve teknik plan:** [Ürün kapsamı ve teknik yön](docs/product-scope.md); mevcut durum/boşluklar, modül ve komut yetkisi sınırları, edge/cloud, entegrasyon matrisi ve aşamalı MVP kabul kriterlerini içerir. Buradaki çalıştırılabilir demolar, plandaki kimlikli ve yetkilendirilmiş ürün MVP'sinin tamamlandığı anlamına gelmez. Mevcut senaryo sözleşmeleri [use-cases.md](docs/use-cases.md) içindedir.

```text
React / TypeScript web
        │ REST / JSON
        ▼
Spring Boot / Kotlin
        │ gRPC / Protobuf
        ▼
Rust / tonic robot-agent
        ├── RobotService → simulation / ros2 (/cmd_vel)
        ├── ElevatorService → asansör case simülasyonu
        ├── DoorService → kapı geçişi simülasyonu
        └── FireAlarmService → ortak alarm/iptal ve simülasyon kurtarma
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

Kapı bölümünde **Geçiş iste** ile başla. Kapı açılışını simüle ettikten sonra **Geçişi başlat** açılır. Robotun geçişini bitirdiğini ve ardından kapının kapandığını simüle ederek akışı tamamla. Kapı açıldığı doğrulanmadan geçiş başlatılamaz; kapanış aşaması robot eşikten çıktıktan sonra başlar. Bu case de fiziksel kapı veya robot hareketi başlatmaz.

Yangın alarmı bölümündeki **Yangın alarmını simüle et**, devam eden kapı/asansör case'lerini `CANCELLED` yapar ve alarm aktifken normal işlemleri engeller. İptal, son bilinen kapı/kat/robot konumunu değiştirmez. **Alarmı temizle** otomatik devam ettirmez; **İptal edilen simülasyonları sıfırla** ile yalnızca iptal edilmiş sanal akışlar başlangıca alınır. Bu açık sıfırlama sanal kapı ve robot durumunu da yeniler; fiziksel bir kurtarma işlemi değildir.

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

Kapı case'inin tamamı, açık kapı önkoşulu ve eski/tekrarlanan olayların reddi için:

```sh
python3 scripts/door_smoke.py
# Web proxy'si üzerinden:
python3 scripts/door_smoke.py --base-url http://localhost:5173
```

Her case'in durumu `IDLE` veya `COMPLETED` olmalıdır; yarım kalmış bir akışı önce arayüzde tamamla. Servis yeniden başlayınca simülasyon durumu sıfırlanır.

Yangın alarmının iki case'i birlikte iptal ettiğini, konumları koruduğunu ve otomatik devam etmediğini doğrulamak için:

```sh
python3 scripts/fire_alarm_smoke.py
# Web proxy'si üzerinden:
python3 scripts/fire_alarm_smoke.py --base-url http://localhost:5173
```

Test öncesinde alarm pasif, kurtarma gerekmiyor ve kapı/asansör case'leri `IDLE` veya `COMPLETED` olmalıdır.

## Kapsam

Bu sürümde tek robot, durum/durdurma işlemleri, bağlantı/hata görünümü, tek asansör/kapı simülasyonları ve normal akışları iptal eden ortak alarm kuralı var. Geçiş ışığı case'i henüz uygulanmadı. Alarm case'i gerçek yangın panelini, kapıyı, asansörü veya ROS hareketini yönetmez. Veritabanı, giriş sistemi, fiziksel hareket, harita ve canlı ROS telemetrisi yok. Dış ağa açmadan önce kimlik doğrulama ve TLS eklenmeli; şimdilik yerel geliştirme içindir.

## Ürün vizyonu ve gelecekteki hedefler

**Bu bölüm planlanan ürün kapsamını anlatır; aşağıdaki hedefler mevcut özellik veya doğrulanmış saha desteği değildir.** Teknik ayrıntılar, adaptör sınırları ve kabul kriterleri [ürün ve teknik planında](docs/product-scope.md) tutulur.

### Çözmek istediğimiz problem

Kendi ortamında çalışan bir robot, müşteri tesisine götürüldüğünde farklı asansörler, kartlı kapılar, ağ politikaları ve bakım şirketleriyle karşılaşır. Her yeni tesiste yeniden mühendislik gerekebilir. Tesis çalışanları kapıyı açmak, robotun kat değiştirmesine yardım etmek veya takılan göreve müdahale etmek zorunda kaldığında otomasyonun beklenen faydası azalır.

Hedefimiz; robotun müşteri tesisinde görevini tamamlamasını sağlayan, kurulumu ve işletilmesi tekrar kullanılabilir bir **robot–bina entegrasyon ürünü** geliştirmek. Yazılım ve gerektiğinde saha donanımı bu sonuca hizmet eder.

| Müşteri adayı | Sağlamayı hedeflediğimiz değer |
| --- | --- |
| Robot üreticisi | Robotu yeni tesislere daha az özel entegrasyon işiyle kurabilmek |
| Robotik entegratörü/distribütörü | Kurulumları tekrarlanabilir hale getirmek ve kurulum sonrası destek yükünü azaltmak |
| Tesis işletmesi | Katlar ve kontrollü alanlar arasında daha az rutin insan müdahalesiyle tamamlanan görevler |
| Çok tesisli işletme | İlk kurulumdaki yazılım, yapılandırma ve doğrulama bilgisini sonraki tesislerde yeniden kullanabilmek |

Bu faydalar ürün hedefleridir. İlk müşteri segmenti, pilot tesis ve gelir modeli henüz doğrulanmış ticari kararlar değildir.

### Entegrasyon yaklaşımı

1. Uygun ve belgelenmiş bir üretici API'si varsa resmî API üzerinden bağlantı.
2. Tesiste mevcut kontrolcü varsa cihazın belgelenmiş arayüzü üzerinden bağlantı.
3. Uygun arayüz yoksa ve saha incelemesi desteklenebilir bir bağlantı gösteriyorsa saha donanımı, firmware ve gateway üzerinden entegrasyon.

Hedef cihazlar asansörler, otomatik/kartlı kapılar, erişim sistemleri, bariyerler, turnikeler, konveyörler, fiziksel trafik ışıkları ve yangın alarmı olaylarıdır. Yürüyen bant ve yürüyen merdivenler için izleme ve rota kısıtları değerlendirilebilir; robotun bunlara binebileceği varsayılmaz. Özel kart geliştirmek ilk kurulumun önkoşulu değildir; mevcut endüstriyel gateway/I/O ürünleri uygunsa değerlendirilebilir. Marka adından cihaz tipi veya protokol çıkarılmaz; her modelin komutları, geri bildirimleri ve güvenlik sınırları dokümantasyonla doğrulanmalıdır. Her cihazın bağlanabileceği taahhüt edilmez.

### Planlanan yetenekler

- **İlk ürünün temelleri:** Robot, kullanıcı, servis ve gateway kimlikleri; site/resource/task kapsamlı yetkilendirme; desteklenen bağlantılarda sertifika/mTLS; doğrulanmış cihaz durumu, hata yönetimi ve kalıcı audit. Yetkisiz istek fiziksel komut üretememeli; komut kabulü fiziksel sonuç olarak gösterilmemeli.
- **Geçiş ve görev yönetimi:** Kapı/asansör adımları, rezervasyon, geçiş sırası ve öncelik, izinli bekleme noktaları, robotun desteklediği hedef/bekle/devam/iptal işlemleri ve operatöre devir. Alarm, arıza veya bağlantı kaybı davranışı tesis prosedürüne bağlanmalı; bina can güvenliği kilitleri geçersiz kılınmamalı.
- **Farklı üreticiler ve robot arayüzleri:** Robotla doğrudan veya üreticinin filo yöneticisi üzerinden bağlantı; ROS 2, VDA 5050, Open-RMF ve MassRobotics için ayrı, capability tabanlı adaptör sınırları. Bunlar eşdeğer işlevler sağlamaz; yalnızca doğrulanmış sürüm ve cihaz yetenekleri sunulmalı. Fiziksel trafik ışığı, Open-RMF'nin `Traffic Light` koordinasyon arayüzünden ayrı ele alınmalı.
- **Tesis koordinasyonu:** Çoklu robot ve ortak kaynak kullanımı, kat/harita/frame dönüşümleri ve açık komut sahipliği. Platform politika ve görev kararlarını yönetirken lokal navigasyon ve çarpışma önleme robotta kalmalı; bağımsız bileşenler aynı robota çelişkili komut göndermemeli.
- **Edge ve çok tesisli yönetim:** Yerel gateway'de adaptör, görev ve politika yürütme; cloud tarafında envanter, yapılandırma, izleme, audit ve güncelleme yönetimi. AWS IoT Greengrass V2 planlanan bir dağıtım seçeneğidir; temel iş mantığı onsuz da yerelde çalışıp test edilebilmeli. Offline yetki sınırlandırılmalı, reconnect'te süresi geçmiş hareket komutları yeniden oynatılmamalı.

### Tesis olayından doğrulanmış sonuca

Robotun geçiş talebinden bağımsız çalışan `SourceListener` adaptörleri hedeflenir. Abonelik, bildirim veya polling yöntemi doğrulanmış cihaz profiline göre seçilir. Alarm, cihaz arızası, bağlantı kaybı ve eski veri ayrı durumlardır; sinyal gelmemesi **alarm yok** anlamına gelmez. Normalleştirilen olayın kaynak kimliği, zamanı ve veri kalitesi korunmalıdır.

`FacilityEvent`, tesisin `SiteEmergencyPolicy` kurallarına göre değerlendirilir. `RobotEventPort` üzerinden desteklenen olay bildirimi ile `RobotCommandPort` üzerinden yetkili komut farklı çıktılardır; olay bildirimi görev iptali veya fiziksel duruş sayılmaz. Komut için kimlik/yetki, capability ve komut sahipliği kontrol edilir; `CommandAccepted` ile `VerifiedOutcome` ayrı izlenir. Okuma, yazma ve geri bildirim yetenekleri birbirinden bağımsız doğrulanır: rölenin sürülmesi veya çıkışın okunması kapının fiziksel olarak açıldığını kanıtlamaz.

### Profil tabanlı devreye alma

Planlanan dashboard akışı: doğrulanmış model/firmware ve arayüz profili seçimi → kaynak/gateway ve I/O anlamlarının tanımı → resource/kat/harita eşlemesi → politika, etkilenen robotlar ve yetki ataması → bağlantı/arıza/eski veri ve salt okunur testler → izinli eylemlerin ayrı sonuç doğrulaması → yetkili onayla sürümlü yapılandırmayı yayınlama. Salt okunur bir profil kendiliğinden yazma yetkisi kazanmaz; devreye alma testi sahada onaylanmamış bir fiziksel komut üretmemelidir. Bu ekranlar ve profil yayınlama akışı henüz uygulanmadı.

### Aday saha donanımı paketleri

| Planlanan paket | Amaç ve sınır |
| --- | --- |
| `EventCollectionGateway` | Belgelenmiş kaynaklardan olay/durum toplama; cihazı kontrol edebildiği varsayılmaz |
| `PassageIntegrationGateway` | Onaylı kapı/bariyer/turnike bağlantılarında komut ve bağımsız fiziksel geri bildirim sınırı |
| `RedundantEdgeRuntime` | Yerel süreklilik için aday; tek aktif komut yazarı, doğrulanmış fencing ve kontrollü devir olmadan yedek node hareket yetkisi alamaz |

Bunlar hazır donanım ürünleri değildir. Önce uygun endüstriyel gateway/I/O seçenekleri değerlendirilir; özel kart ve firmware ihtiyaca göre geliştirilir. Üretici, yetkili tesis ekipleri ve saha entegrasyon ortaklarıyla elektriksel anlamlar, kurulum sorumluluğu ve doğrulama kapsamı belirlenmelidir.

### Teknik doğrulamadan ticari pilota

| Aşama | Hedef |
| --- | --- |
| İlk teknik dikey dilim | Bir simüle robot + bir simüle kapı: kimlik → yetki → kapı komutu → doğrulanmış açık kapı → geçiş izni → robotun çıkış teyidi → audit |
| Ortak kaynak koordinasyonu | İki robot, iki kat, bir asansör ve bir kapı; kimlikli rezervasyon, bekleme ve doğrulanmış çıkıştan sonra kaynak bırakma |
| Hedef ticari pilot | Bir gerçek tesis, bir robot modeli, bir asansör, bir kontrollü kapı ve iki kat arasında tekrarlanan tek bir müşteri görevi; kapsam sahadaki belgelenmiş arayüzlere göre seçilmeli |
| Ürünleşme | Sonraki kurulumlarda daha az özel mühendislik, daha fazla adaptör/yapılandırma yeniden kullanımı; kurulu taban büyüdükçe çoklu üretici ve çok tesisli operasyon |

Pilotlarda insansız tamamlanan görev oranı, her 100 görevde müdahale sayısı ve süresi, kapı/asansör bekleme süreleri, kurulum mühendislik zamanı ve sonraki kurulumdaki yeniden kullanım ölçülmek istenir. İlk saha çalışması işlevi, sonraki kurulumlarda azalan iş yükü ürünleşme potansiyelini gösterecektir. Henüz ölçülmüş ticari başarı veya sayısal hedef iddiası yoktur.

---

<a id="english"></a>

# Robot–building integration starter

[Türkçe](#turkce) · [English](#english)

A small starting point for scenarios in which robots interact with doors, elevators, and building events. It includes **elevator calling and floor selection**, **electronic door passage**, and **cancellation of passages during a simulated fire alarm**, while retaining the robot status screen and **stop** command. It starts in simulation mode without requiring hardware.

**Product and technical plan:** [Product scope and technical direction](docs/product-scope.md) covers the current state and gaps, module and command authority boundaries, edge/cloud responsibilities, the integration matrix, and staged MVP acceptance criteria. That detailed document is currently in Turkish. The runnable demos do not mean the planned authenticated and authorized product MVP is complete. Current scenario contracts are documented in [use-cases.md](docs/use-cases.md), also in Turkish.

```text
React / TypeScript web
        │ REST / JSON
        ▼
Spring Boot / Kotlin
        │ gRPC / Protobuf
        ▼
Rust / tonic robot-agent
        ├── RobotService → simulation / ros2 (/cmd_vel)
        ├── ElevatorService → elevator scenario simulation
        ├── DoorService → door passage simulation
        └── FireAlarmService → shared alarm/cancellation and simulation recovery
```

## Quick start

With Docker and Docker Compose v2, run from the project root:

```sh
docker compose up --build -d
```

- Web: <http://localhost:5173>
- REST API: <http://localhost:8080/api/robot/status>
- Logs: `docker compose logs -f`
- Shut down: `docker compose down`

Dependencies are downloaded during the first build. The screen shows a waiting state until the connection is established. The gRPC service is exposed only inside the Compose network; the web and REST ports are bound to the local machine.

The robot simulation initially returns `IDLE`, 84% battery, and a speed of 0 m/s. Stopping changes its state to `STOPPED`; stopping it again has the same outcome. State is held in memory and resets when the Rust service restarts. Battery and speed are simulation constants, not real sensor measurements.

The UI is currently in Turkish; the button labels below match the actual interface.

In the elevator section, choose the pickup floor and click **Asansörü çağır** (call the elevator). Simulate arrival/door opening and robot boarding, then select the destination floor. Simulate arrival and exit to complete the scenario. Supported simulation floors are 0–5. The elevator scenario always runs in simulation mode and does not initiate physical robot or elevator movement. See the [scenario contracts and future integrations](docs/use-cases.md).

In the door section, start with **Geçiş iste** (request passage). After simulating door opening, **Geçişi başlat** (start passage) becomes available. Simulate the robot completing its passage, followed by the door closing. Passage cannot start before door opening is confirmed, and the closing phase starts only after the robot has cleared the doorway. This scenario does not initiate physical door or robot movement either.

In the fire alarm section, **Yangın alarmını simüle et** (simulate a fire alarm) marks ongoing door/elevator scenarios as `CANCELLED` and blocks normal operations while the alarm is active. Cancellation preserves the last known door, floor, and robot position information. **Alarmı temizle** (clear the alarm) does not automatically resume anything. **İptal edilen simülasyonları sıfırla** (reset cancelled simulations) resets only cancelled virtual scenarios. This explicit reset also resets virtual door and robot state; it is not a physical recovery operation.

## Local development

Requirements: Rust 1.93+, JDK 21, and Node.js 22.12+ or 24+. The Gradle Wrapper is included. The Protobuf compiler is downloaded as a Rust build dependency and by Gradle for Java code generation.

From the project root, use three separate terminals:

```sh
# 1. Rust gRPC service, 127.0.0.1:50051
cd robot-agent
cargo run --locked
```

```sh
# 2. Kotlin REST API, :8080
cd backend
./gradlew bootRun
```

```sh
# 3. Web development server, :5173
cd frontend
npm ci
npm run dev
```

Vite proxies web `/api` requests to the local API during development. In Docker, nginx proxies them to the backend service. No CORS configuration is needed for this setup.

| Variable | Default | Used by |
| --- | --- | --- |
| `GRPC_ADDR` | `127.0.0.1:50051` | Rust listening address |
| `ROBOT_MODE` | `simulation` | Rust; `simulation` or `ros2` |
| `ROS_CMD_VEL_TOPIC` | `/cmd_vel` | ROS 2 stop command topic |
| `ROBOT_GRPC_HOST` | `localhost` | Kotlin → Rust |
| `ROBOT_GRPC_PORT` | `50051` | Kotlin → Rust |
| `SERVER_PORT` | `8080` | REST API port |
| `API_PROXY_TARGET` | `http://127.0.0.1:8080` | Vite → REST API |

## API and shared contract

The shared contract is [`proto/robot.proto`](proto/robot.proto). Kotlin and Rust generate their gRPC code from the same file.

```sh
curl http://localhost:8080/api/robot/status
curl -X POST http://localhost:8080/api/robot/stop
```

Example `GET /api/robot/status` response:

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

`POST /api/robot/stop` returns `{ "accepted": true, "message": "...", "status": { ... } }`. If Rust cannot be reached, REST returns `503`; a gRPC timeout returns `504`. Errors use `{ "code": "...", "message": "..." }`. The screen refreshes status every two seconds and does not present the last known data as current when the connection is lost.

## ROS 2 connection

At this stage, the ROS 2 integration supports **only publishing a stop command**. In a terminal with ROS 2 and the robot environment installed and the relevant setup/workspace sourced:

```sh
source /opt/ros/jazzy/setup.bash
# If required: source /path/to/robot_ws/install/setup.bash
cd robot-agent
ROBOT_MODE=ros2 ROS_CMD_VEL_TOPIC=/cmd_vel cargo run --locked
```

The Rust adapter uses `ros2 topic pub --once --wait-matching-subscriptions 0` to publish a `geometry_msgs/msg/Twist` message with every axis set to zero. Configure the topic to match the robot, and configure the ROS domain and middleware environment in the same terminal if needed. The default Compose image does not include ROS; run ROS mode in a local ROS environment.

In ROS mode, battery and speed are `null`, and the initial state is `UNKNOWN`. A successful publish produces `STOP_REQUESTED`. This confirms publication, not that the physical robot has stopped. `observedAtUnixMs` is the service response time, not a ROS sensor measurement timestamp. A failed or timed-out publish returns an API error. Real ROS and hardware connectivity must be validated separately from simulation tests.

**The stop button sends a software command; it is not a physical emergency stop system.** Another publisher or the robot controller may override the zero-velocity message.

## Validation

```sh
cd robot-agent && cargo test --locked
cd ../backend && ./gradlew test --no-daemon
cd ../frontend && npm ci && npm run build
```

With the services running, execute the robot simulation smoke test from the project root:

```sh
python3 scripts/smoke.py
# Also through the web API proxy:
python3 scripts/smoke.py --base-url http://localhost:5173
```

The smoke test does not send a command in real ROS mode; it attempts to stop the robot only after receiving a `simulation` response.

To check the full elevator scenario and rejection of out-of-order operations:

```sh
python3 scripts/elevator_smoke.py
# Through the web proxy:
python3 scripts/elevator_smoke.py --base-url http://localhost:5173
```

To check the full door scenario, the open-door prerequisite, and rejection of stale/repeated events:

```sh
python3 scripts/door_smoke.py
# Through the web proxy:
python3 scripts/door_smoke.py --base-url http://localhost:5173
```

Each scenario must be in `IDLE` or `COMPLETED` before its test. Complete an unfinished flow in the UI first. Restarting the service resets simulation state.

To verify that a fire alarm cancels both scenarios together, preserves positions, and does not automatically resume them:

```sh
python3 scripts/fire_alarm_smoke.py
# Through the web proxy:
python3 scripts/fire_alarm_smoke.py --base-url http://localhost:5173
```

Before this test, the alarm must be inactive, no recovery must be required, and both door/elevator scenarios must be in `IDLE` or `COMPLETED`.

## Current scope

This version includes one robot, status/stop operations, connection/error feedback, one simulated elevator and door, and a shared alarm rule that cancels normal workflows. The traffic light scenario is not implemented yet. The alarm scenario does not control a real fire panel, door, elevator, or ROS movement. There is no database, sign-in system, physical movement, map support, or live ROS telemetry. Authentication and TLS must be added before exposing the application externally; it is currently intended for local development.

## Product vision and future goals

**This section describes planned product scope, not implemented features or verified field support.** Technical details, adapter boundaries, and acceptance criteria remain in the [product and technical plan](docs/product-scope.md).

### The problem we aim to solve

A robot that works in its own environment encounters different elevators, card-controlled doors, network policies, and maintenance companies at a customer site. Each new site may require engineering work again. When staff must open doors, help the robot change floors, or intervene in stalled tasks, the expected benefit of automation decreases.

Our goal is a **robot–building integration product** that enables robots to complete customer tasks through repeatable deployment and operation. Software and field hardware, when required, serve that outcome.

| Potential customer | Intended value |
| --- | --- |
| Robot manufacturer | Deploy robots at new sites with less custom integration work |
| Robotics integrator/distributor | Make installations repeatable and reduce post-installation support effort |
| Facility operator | Complete tasks across floors and controlled areas with less routine human intervention |
| Multi-site organization | Reuse software, configuration, and validation knowledge from the first deployment at subsequent sites |

These benefits are product goals. The first customer segment, pilot site, and revenue model are not yet validated commercial decisions.

### Integration approach

1. Use the official manufacturer API when a suitable, documented API is available.
2. Connect through an existing controller's documented interface when the site has one.
3. Use field hardware, firmware, and a gateway when no suitable interface exists and a site assessment identifies a supportable connection.

Target equipment includes elevators, automatic/card-controlled doors, access systems, barriers, turnstiles, conveyors, physical traffic lights, and fire alarm events. Monitoring and route constraints may be evaluated for moving walkways and escalators; robots must not be assumed capable of boarding them. A custom circuit board is not a prerequisite for the first installation; existing industrial gateways and I/O products may be evaluated where suitable. Device type or protocol must not be inferred from a brand name. Commands, feedback, and safety boundaries must be verified for each model from its documentation. Support for every device is not promised.

### Planned capabilities

- **First-product foundations:** Separate robot, user, service, and gateway identities; site/resource/task-scoped authorization; certificates/mTLS on supported connections; confirmed device state, failure handling, and durable audit. Unauthorized requests must not produce physical commands, and command acceptance must not be presented as a confirmed physical result.
- **Passage and task management:** Door/elevator steps, reservations, passage order and priority, approved waiting points, supported robot navigation/wait/resume/cancel operations, and operator handoff. Alarm, fault, and connectivity-loss behavior must follow site procedures without overriding building life-safety interlocks.
- **Different manufacturers and robot interfaces:** Connect directly to a robot or through its manufacturer's fleet manager, with separate capability-based adapter boundaries for ROS 2, VDA 5050, Open-RMF, and MassRobotics. These do not provide equivalent functionality; only verified versions and device capabilities should be exposed. Physical traffic lights must be treated separately from Open-RMF's `Traffic Light` coordination interface.
- **Facility coordination:** Multiple robots and shared resources, floor/map/frame transformations, and explicit command ownership. The platform manages policies and task decisions while local navigation and collision avoidance remain on the robot. Independent components must not send conflicting commands to the same robot.
- **Edge and multi-site management:** Run local adapters, tasks, and policies on a gateway; manage inventory, configuration, monitoring, audit, and updates in the cloud. AWS IoT Greengrass V2 is a planned deployment option; core logic should also run and be tested locally without it. Offline authority must be bounded, and expired movement commands must not be replayed after reconnection.

### From a facility event to a verified outcome

Planned `SourceListener` adapters operate independently of a robot requesting passage. Subscription, notification, or polling is selected for the verified device profile. Alarm, device fault, connectivity loss, and stale data are separate states; **no signal does not mean no alarm**. Normalized events must preserve source identity, time, and data quality.

A `FacilityEvent` is evaluated under the site's `SiteEmergencyPolicy`. A supported event notification through `RobotEventPort` and an authorized command through `RobotCommandPort` are different outputs; an event notification does not count as task cancellation or physical stopping. Commands require identity/authorization, capability, and command ownership checks; `CommandAccepted` and `VerifiedOutcome` are tracked separately. Read, write, and feedback capabilities are verified independently: driving a relay or reading an output does not prove that a door physically opened.

### Profile-based commissioning

The planned dashboard flow is: select a verified model/firmware and interface profile → define source/gateway and I/O meanings → map resources, floors, and maps → assign policies, affected robots, and authority → run connectivity/fault/stale-data and read-only tests → verify the separate outcomes of permitted actions → publish versioned configuration with authorized approval. A read-only profile does not automatically gain write authority, and a commissioning test must not produce a physical command that has not been approved for that site. These screens and the profile publishing workflow are not implemented yet.

### Candidate field hardware packages

| Planned package | Purpose and boundary |
| --- | --- |
| `EventCollectionGateway` | Collect events/status from documented sources; device control must not be assumed |
| `PassageIntegrationGateway` | A boundary for commands and independent physical feedback on approved door/barrier/turnstile connections |
| `RedundantEdgeRuntime` | A candidate for local continuity; a standby node cannot gain movement authority without a single active command writer, verified fencing, and controlled handoff |

These are not ready-made hardware products. Suitable industrial gateway/I/O options are evaluated first; custom boards and firmware are developed as needed. Manufacturers, authorized site teams, and field integration partners must establish electrical meanings, installation responsibilities, and the scope of validation.

### From technical validation to a commercial pilot

| Stage | Goal |
| --- | --- |
| First technical vertical slice | One simulated robot and one simulated door: identity → authorization → door command → confirmed open door → passage grant → robot exit confirmation → audit |
| Shared-resource coordination | Two robots, two floors, one elevator, and one door; identity-bound reservations, waiting, and resource release after confirmed exit |
| Target commercial pilot | One real site, one robot model, one elevator, one controlled door, and a single repeated customer task across two floors; scope must follow the site's documented interfaces |
| Productization | Less custom engineering and more reusable adapters/configuration in subsequent installations; multi-manufacturer and multi-site operations as the installed base grows |

Pilot measurements should include the proportion of tasks completed without human intervention, intervention count and duration per 100 tasks, door/elevator waiting times, installation engineering time, and reuse in subsequent installations. The first field deployment should demonstrate functionality; reduced effort in subsequent deployments should demonstrate productization potential. No measured commercial success or numerical target is claimed yet.
