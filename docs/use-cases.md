# Robot–bina entegrasyonu

Bu dosya **bugün uygulanan simülasyonları** ve **henüz uygulanmamış, planlanan case'leri** ayrı bölümlerde tanımlar. Ürün vizyonu, hedef mimari, güvenlik/komut yetkisi ve sürüm sınırlarının tek referansı [Ürün kapsamı ve teknik yön](product-scope.md) belgesidir. Buradaki mevcut durum akışları kimliği doğrulanmış robot görev yürütmesi veya saha doğrulaması değildir.

Amaç; robotların elektronik kapı, asansör, geçiş ışığı ve yangın alarmı gibi bina sistemleriyle ortak senaryolar üzerinden çalışmasını sağlamak. Ürün çekirdeği komutları, cihaz geri bildirimlerini ve robotun görev adımlarını birbirine bağlar.

Mevcut web arayüzü operatörün senaryoyu izlemesini ve simülasyonu denemesini sağlar. Kotlin backend REST sınırını ve istek doğrulamasını, Rust servisi gRPC üzerinden robot/cihaz bağlantısı sınırını sağlar. Mevcut sürümde süreç durumu bellekte tutulur.

| Bölüm | Destek durumu | Ne ifade eder? |
| --- | --- | --- |
| Aşağıdaki asansör, kapı ve sabit alarm iptal akışları | `SIMULATED` | Kodda çalışan, elle ilerletilen üç case; bu bölümlerdeki REST uçları mevcuttur |
| Planlanan tesis alarmı, erişim geçişi, yürüyen yol/merdiven, konveyör ve devreye alma case'leri | `PLANNED` | Hedef davranış ve kabul koşulları; adaptör, yeni API veya saha desteği henüz yoktur |

Hiçbir case bu belgeyle `FIELD_VERIFIED` sayılmaz. Mevcut üç simülasyonun aşağıdaki durum/API sözleşmeleri korunur; yeni case'ler en sondaki plan bölümündedir.

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

Bu sabit demo iptal politikası tesisin yangın prosedürü olarak taşınmaz. Hedef üründeki davranış, onaylı `SiteEmergencyPolicy` ve gerçek cihaz/robot yetenekleriyle belirlenecektir; aşağıdaki akış yalnızca mevcut kodun açıklamasıdır.

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

## Planlanan case'ler — `PLANNED`

Bu bölümdeki İngilizce case ve olay adları **dokümantasyon kimlikleridir**; yeni REST/gRPC uçları veya uygulanmış adaptörler tanımlamaz. Mimari sınırlar [tesis olay hattı](product-scope.md#facility-event-pipeline), [cihaz profilleri](product-scope.md#device-profiles) ve [saha paketleri](product-scope.md#field-packages) bölümlerinde tutulur; burada her case'in akışı ve kabul koşulları yer alır.

| Case kimliği | Amaç | Temel sınır |
| --- | --- | --- |
| `FacilityAlarmResponse` | Bağımsız tesis alarmı kaynağını dinle; bölge/prosedüre göre robot görevlerine yansıt | Mevcut sabit demo iptal politikası tesis prosedürü sayılmaz |
| `AccessPassage` | Otomatik kapı, bariyer veya turnikeden izinli geçiş | Çıkış sürme ve fiziksel geçiş kanıtı ayrı yeteneklerdir |
| `MovingWalkwayEscalator` | Ekipman durumunu izle, desteklenmeyen geçişi rotadan çıkar | Başlangıç kapsamı izleme ve rota dışlama; robotun binmesi varsayılan olarak `UNSUPPORTED` |
| `ConveyorTransfer` | Robot–endüstriyel konveyör yük aktarımını eşgüdümle | El sıkışması ve sensör kanıtı gerekir; üretici register'ı varsayılmaz |
| `SiteCommissioning` | Kurulum profilini kur, test et ve onaylı sürüm olarak yayımla | Cihaz/robot yetenekleri kanıtlanır; serbest güvenlik kuralı editörü değildir |

### `FacilityAlarmResponse`: bağımsız kaynak, tesis politikası, robot tepkisi

Planlanan akış: **bağımsız `FacilityAlarm` dinleyicisi → normalize edilmiş `FireAlarmActivated` olayı → zone/site eşlemesi ve onaylı `SiteEmergencyPolicy` → etkilenen robot başına izinli tepki → kabul ve ayrı sonuç doğrulaması**. `SourceListener` robot görevlerinden bağımsız, salt okunur çalışır; kaynak profiline göre abonelik/bildirim veya polling kullanır. Paneli resetleme veya çıkış sürme yetkisi bu dinleyiciden türetilmez. Kaynak olay kimliği/sırası, zaman/kalite ve bölge eşlemesi kaydedilir; olmayan bir cihaz sıra veya correlation alanı uydurulmaz.

Robot başına tepki `EVENT_ONLY`, doğrulanmış `Wait` veya `CancelTask` olabilir. Seçim tesis prosedürü, etkilenen bölge, görev ve robot capability profiline bağlıdır; bütün robotlara aynı eylem gönderilmez. `EVENT_ONLY`, ayrı bildirim yeteneği olan `RobotEventPort` üzerinden; eylemler `RobotCommandPort` üzerinden ele alınır. Bildirim alındısı yalnızca teslim kanıtıdır. Olayı alan robot/entegratör bir hareket başlatacaksa aynı kontrol otoritesi profilini uygulamak zorundadır; bildirim kanalı ikinci bir komut yolu oluşturmaz.

Desteklenmeyen eylem fiziksel dispatch yapılmadan reddedilir ve operatöre bildirilir; `CancelTask` yerine sıfır hız yayınlamak eşdeğer sayılmaz. Komut ACK'i, isteğin kabul edildiğini gösterir; robotun beklediği veya görevin terminal olarak iptal edildiği ayrı geri bildirimle doğrulanır. Tekrarlanan aktif polling örnekleri yeni eylem üretmez: aynı politika/canonical olay veya aktivasyon/hedef/eylem etkisi mevcut `commandId` ile eşleştirilir. Örnek etki anahtarı `pinnedPolicyVersion + logicalActivationId + target + action → commandId` olur; transport delivery kimliği bunun yerine kullanılmaz.

Sıfır robot varken listener çalışmaya devam eder. Aktif alarm sırasında bağlanan robot veya yeni görev, yeni bir activation event beklemeden güncel `FacilityContextSnapshot` üzerinden policy/capability değerlendirmesine girer. Yeni context teslimi, geçmiş fiziksel komutların otomatik yeniden oynatılması değildir.

Başlangıçta taze kaynak snapshot'ı gelene kadar durum `UNKNOWN` olur. Kaynak `DISCONNECTED`, `FAULT` veya `UNKNOWN` olduğunda alarm temizlenmiş sayılmaz; `SourceConnectionLost` ve `DeviceFaultReported`, `FireAlarmCleared` olayı değildir. Yeni normal geçişlerin kabulü ve devam eden işlerin davranışı onaylı tesis prosedürüne göre ele alınır; bağlantı geri geldi diye `Resume` gönderilmez. Alarm temizleme olayı da kendiliğinden devam izni değildir. Eski bir alarm çevrimine veya boot/oturuma ait gecikmiş temizleme, daha yeni aktif alarmı kaldıramaz; kaynak bunu ayırt etmeye yeterli kanıt sağlamıyorsa durum belirsiz kalır ve yeniden doğrulanır.

### `AccessPassage`: kapı, bariyer ve turnike

Planlanan akış: **kimlik/erişim kararı → kaynak rezervasyonu → belgelenmiş açma/geçiş talebi → bağımsız açık/geçişe hazır geri bildirimi → robot geçişi → bütün footprint'in çıkış kanıtı → kaynak bırakma**. Okunabilen girişler ve sürülebilen çıkışlar profilde ayrı eşlenir. Röle ACK'i veya çıkış readback'i kapının açıldığını, bariyerin kalktığını ya da turnikenin robot geçişine uygun olduğunu kanıtlamaz; gerekli feedback yoksa otomatik geçiş verilmez.

Geçiş açıklığı, robotun yük dahil footprint'i ve ekipmanın algılama sınırları eşleştirilir. İnsan algılayan bir sensörün robotu ve yükünü de algıladığı varsayılmaz. Engel, stale sensör veya kayıp çıkış teyidinde doluluk belirsiz tutulur; timeout veya iptal kaynağı boşaltmaz. Mevcut kapı case'i bu saha doğrulamalarını içermeyen `SIMULATED` akış olarak kalır.

### `MovingWalkwayEscalator`: önce izleme ve rota dışlama

Başlangıç akışı: **belgelenmiş ekipman durumunu oku → veri kalitesini değerlendir → robot/model için rota uygunluğunu belirle → destek yoksa geçişi rotadan çıkar**. İlk kapsam ekipmana başlat/durdur/yön komutu veya robota binme komutu içermez. `STOPPED` gözlemi bile tek başına geçilebilir alan kanıtı değildir.

Binme ancak ekipman, robot/yük ve tesis prosedürü için doğrulanmış ortak capability ve test kanıtı varsa ileride ayrı bir kapsam olabilir. Varsayılan `UNSUPPORTED` sonucudur; hareket eden sisteme uygunluk uydurulmaz. Kaynak kaybında rota dışlama kendiliğinden kalkmaz.

### `ConveyorTransfer`: endüstriyel yük aktarımı

Örnek sözleşme akışı: **`READY → TRANSFER_REQUESTED → TRANSFER_IN_PROGRESS → TRANSFER_COMPLETED`**. `BUSY`, yeni aktarımın kabul edilmediğini gösterir. Bu adlar mantıksal el sıkışma örneğidir; herhangi bir PLC'nin bit/register adresleri veya üretici protokolü değildir. Gerçek read/write yönleri, hazır/işgal anlamı ve timeout değerleri seçilen kontrolcü profilinde belgelenir.

Robot hizası, yük varlığı ve aktarım bölgesinin durumu gerekli sensör/robot kanıtlarıyla doğrulanır. Komut ACK'i aktarımın başladığı veya yükün teslim edildiği anlamına gelmez. Dispatch sonrası yanıtsızlıkta sonuç `OUTCOME_UNKNOWN` kalabilir; kör retry yapılmaz. Aktarım tamamlanması da tek başına robotun/alanın boşaldığını göstermez: doluluk ve kaynak tutma, ayrıca clearance kanıtı gelene kadar korunur.

### `SiteCommissioning`: müşteri devreye alma paneli

Planlanan akış: **cihaz profili seç → gateway/ağ bağlantısını kur → giriş okuma/çıkış sürme eşlemelerini ayır → mantıksal kaynak ve zone/site bağla → onaylı politika ve robot capability profillerini ata → salt okunur testler → yetkili kontrollü komut testleri → onay → sürümlü yayımlama ve audit**. Profil üretici/model/firmware, ağ ve I/O sınırlarını taşır; bilinmeyen değerler kurulum tamamlanmış gibi doldurulmaz.

Panel, beyan edilen capability ile testte gözlenen capability'yi ve kanıtın kapsamını ayrı gösterir. Test sonucu, zaman/kalite, kullanılan model/firmware ve profil sürümü kayıtlı olur; başarısız veya doğrulanamayan test `FIELD_VERIFIED` üretmez. Komut testleri yetkili devreye alma koşullarında çalışır. Yayımlama yalnızca onaylı sürümü etkinleştirir; daha sonraki değişiklik yeni sürüm/test/onay gerektirir. Kullanıcıya keyfi safety interlock veya tesis yangın kuralı yazdırılmaz; panel yalnızca onaylı profil/politika seçeneklerini bağlar.

### Planlanan kabul kontrolleri

Bu kontroller gelecek implementasyon ve saha profili içindir; mevcut testlerin geçtiği iddiası değildir.

| Kontrol | Beklenen kanıt/sonuç |
| --- | --- |
| Alarm kaynağı aboneliği veya polling | Kaynak profilinin yönteminde aynı olay/bölge normalize edilir; robot başına politika kararı kaydedilir. Reconnect sonrası yeni delivery kimliğiyle gelen aynı logical activation dahil, aynı etki için ikinci komut dispatch'i sıfırdır |
| Robot/görevden bağımsız dinleme | Sıfır robot varken listener izler; aktif alarm sırasında bağlanan robot current-state snapshot üzerinden değerlendirilir, yeni activation event beklenmez |
| Başlangıç/kaynak kaybı/yanıtsızlık | Taze snapshot öncesi `UNKNOWN`; bağlantı kaybı `DISCONNECTED`, eski/yanıtsız gözlem `STALE`/`UNKNOWN` olur. `FAULT` yalnız belgelenmiş cihaz arıza bildirimiyle gösterilir. Bunlar alarmı temizlemez, otomatik `Resume` yoktur; kaynak geri gelince taze durum uzlaştırılır |
| Eski clear, yeni active | Eski boot/oturum veya alarm çevriminden sonra ulaşan temizleme yeni aktif çevrimi düşürmez; sıra/çevrim kanıtı yoksa belirsizlik açık kalır |
| ACK var, eylem kanıtı yok | Bildirim alındısı hareket kanıtı değildir. `Wait`/`CancelTask`, kapı açma veya konveyör ACK'i de fiziksel başarıya dönüşmez; ayrı sonuç beklenir, deadline'da bilinmeyen sonuç ve operatör ihtiyacı gösterilir |
| Desteklenmeyen robot/ekipman eylemi | Komut adaptörü çağrısı ve fiziksel dispatch sayısı **sıfır**; açık `UNSUPPORTED`, kayıtlı ret ve gerekiyorsa rota dışlama vardır |
| I/O hatası ve kablo kopması | Seçilen giriş devresinin gözetim/algılama yeteneği test edilir; tel kopması normal/kapalı/clear sayılmaz. Gözetimsiz giriş kopmayı ayırt edemiyorsa bu sınır profilde gösterilir, capability doğrulanmış sayılmaz ve normal durum kanıtı üretilmez |
| Açıklık/footprint/algılama sınırı | Kapı, bariyer veya turnike için robot/yük uyumu ve çıkış kanıtı test edilir; röle ACK'iyle geçiş açılmaz, sensör/çıkış kaybında doluluk tutulur |
| Yürüyen yol/merdiven | Başlangıçta yalnızca izleme/rota dışlama; doğrulanmış ortak capability yoksa binme komutu yoktur; stale/kayıp kaynak geçişi uygun hale getirmez |
| Konveyör el sıkışması ve timeout | `BUSY` yeni aktarımı engeller; sensör kanıtı olmadan tamamlandı olmaz; ACK kaybı, timeout veya eksik clearance doluluğu ve kaynak tutmayı kaldırmaz |
| Devreye alma/yayımlama | Salt okunur ve kontrollü test kanıtları doğru profile bağlıdır; yetkisiz/onaysız sürüm etkinleşmez, yayımlayan aktör/sürüm/onay/test sonucu audit'te bulunur; yayımlama anlık fiziksel hareket başlatmaz |

Fiziksel geçiş ışığı ve robot trafik koordinasyonundaki “Traffic Light” ayrı konulardır. Hangi kontrolcü ve saha senaryosunun hedeflendiği ayrıca netleştirilecek; bu plan bunlar için protokol veya cihaz desteği ilan etmez.
