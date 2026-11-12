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

<a id="unloc-delivery"></a>

## Planlanan Unloc teslimat case'leri — `PLANNED`

Pilot hedefi **tek robot, tek bina girişi ve girişten asansör gerektirmeden ulaşılabilen ortak teslimat alanıdır**. Tek girişin çıkış yönündeki kullanım yöntemi de ayrıca doğrulanır; giriş yetkisi çıkış izni/kanıtı değildir. Daire içi teslimat, asansörlü rota ve çok binalı operasyon sonraki kapsamlardır. Aşağıdaki UC-03, sonraki çok kapılı genişlemeyi ve ortak çıkış koşullarını tanımlar; pilotta çok kapılı/asansörlü teslimatın hazır olduğu anlamına gelmez.

**Kullanıcı gözlemi:** bu binada Unloc kullanımıyla kilidin açıldığı ve motorlu kapı kanadının açıldığı bildirildi. Bu gözlem, API'nin motoru tetiklediğinin veya fiziksel açık/kilit durumunu raporladığının teknik doğrulaması değildir. Bu bina/proje için kilit/kontrolcü modeli, online/Bluetooth erişim yolu, API kullanım hakkı ve yetki kapsamı; motor tetikleme, açık/kilit feedback'i, açık tutma, geçiş algılama ve yeniden kilitleme yetenekleri henüz doğrulanmamıştır. Resmî kaynaklar, yetenek/destek matrisi ve sorumluluk sınırları için tek referans [Unloc erişim kapsamı](product-scope.md#unloc-access) bölümüdür.

Bu case'ler dokümantasyon tasarımıdır; mevcut üç simülasyona Unloc adaptörü, yeni REST/gRPC ucu, SDK veya saha desteği eklemez. Aşağıdaki açılma/geçiş adımları, ilgili capability ve kanıt kaynağı doğrulanmadan otomatik olarak çalıştırılamaz. Kilit işleminin sağlayıcı tarafından doğrulandığı yanıt dahil Unloc isteğinin kabulü ile **taze, güvenilir fiziksel `DoorOpen` ve bütün robot/yük footprint'inin clearance kanıtı ayrıdır**. Eski, başka kapıya/göreve ait veya güvenilmeyen feedback geçiş izni üretmez; erişim olayı/bildirim kaydı fiziksel sonuç değildir. Mobil anahtar bildirimlerinin doğrudan API işlemlerini de kapsadığı varsayılmaz.

### UC-01 — Ortak girişten ortak teslimat alanına erişim

**Aktörler:** teslimat robotu, görev veren yetkili kullanıcı/entegratör, platform görev/erişim koordinatörü, onaylı erişim adaptörü ve bina operatörü.

**Önkoşullar:** pilot bina girişi ve ortak teslimat alanı haritada tanımlı; robot/yük açıklığa uygun; bina sahibinin entegrasyon izni, kullanılabilir erişim hakkı, kapı feedback/clearance yöntemi ve dönüşte çıkış yöntemi doğrulanmış olmalıdır. Robotun tek `CommandOwner`'ı ve kapı kaynağının komut sahibi atanır. Doğrulanamayan API veya fiziksel açılma yeteneğiyle otonom geçişe başlanmaz.

**Tetikleyici:** izinli teslimat görevindeki robot, bina girişindeki tanımlı yaklaşma/bekleme noktasına ulaşır.

**Ana akış:**

1. Platform görev, robot, bina/kapı ve zaman kapsamını değerlendirir; geçiş kaynağını göreve ayırır.
2. Son gönderim sınırı yetki/politika sürümünü, capability'yi ve kontrol sahipliğini yeniden doğrular; yalnız doğrulanmış erişim arayüzüne izinli talep gönderilir.
3. Talep ACK'i kaydedilir. Robot, kapının fiziksel olarak açıldığını ve geçiş açıklığının uygun olduğunu gösteren taze kanıt gelene kadar bekler; ACK ile geçiş başlatılmaz.
4. Robot hareketini mevcut tek `CommandOwner` yürütür. RMF veya vendor fleet manager bu owner ise platform ona paralel ikinci bir hareket yolu açmaz.
5. Robotun yük dahil tamamının giriş eşiğini geçtiği doğrulanır; kaynak ancak gerekli clearance koşullarıyla bırakılır. Robot ortak teslimat alanına ilerler ve varışı doğrulanır.

**Hata durumları:** izin reddi veya doğrulanmamış capability'de fiziksel talep yoktur. ACK sonrası kapı açılmazsa, feedback eski/güvenilmezse veya engel algılanırsa geçiş verilmez; UC-04 hata/devralma akışı uygulanır. Kilit açıldı bilgisinden motorlu kanadın açıldığı sonucu çıkarılmaz.

**Başarı kriteri:** yetkili giriş, doğrulanmış tam geçiş ve ortak teslimat alanına varış kanıtı aynı görevle ilişkilidir. Paketin teslim edildiği iddiası ayrıca görev profilinin teslim kanıtını gerektirir; alana varış veya unlock ACK'i paket teslim kanıtı değildir.

### UC-02 — Görev ve zamanla sınırlı erişim

**Aktörler:** görev sahibi/yetkili kullanıcı, platform kimlik ve erişim politikası, robot, erişim adaptörü ve yetkili bina yöneticisi.

**Önkoşullar:** erişim kararı robot/görev/bina/kapı ve açık zaman aralığıyla bağlı; saat/zaman dilimi ve credential yetki kapsamı doğrulanmış olmalıdır. Unloc'un bu sınırları kendi arayüzünde nasıl temsil ettiği bilinmiyorsa sağlayıcı özelliği uydurulmaz; platform kendi gönderim sınırını uygular ve gerekli sağlayıcı kısıtlarını ayrıca doğrulatır.

**Tetikleyici:** görevin giriş/çıkış için erişim talep etmesi veya bekleyen talebin zaman/yetki koşullarının değişmesi.

**Ana akış:**

1. Platform izinli göreve, robota ve kapıya bağlı süreli erişim kararını oluşturur; bu karar genel ve süresiz bina erişimi sayılmaz.
2. Dispatch anında görev hâlâ geçerli mi, zaman aralığı açık mı, yetki/politika ve kontrol otoritesi güncel mi yeniden denetlenir.
3. İzinli talebin kimliği, sonucu ve yerel deduplication kaydı platformda tutulur; yinelenen talep kendiliğinden yeni fiziksel işlem üretmez. Sağlayıcıdaki bir korelasyon/trace alanının idempotency sağladığı varsayılmaz. Kabul ACK'i ile kapı açılma sonucu ayrı izlenir.
4. Görev veya erişim süresi bittiğinde yeni gönderim/admission kapanır. Daha önce gönderilmiş talebin fiziksel sonucu ayrıca uzlaştırılır; robotun eşikte olduğu varsayılan süreyle silinmez.

**Hata durumları:** başka görev/robot/kapı, süresi geçmiş izin, iptal edilmiş hak veya doğrulanamayan saat/yetki yeni dispatch üretmez. Gönderimden sonra süre dolması ya da yanıt kaybı, kapının hiç açılmadığını veya yeniden kilitlendiğini kanıtlamaz. Kör retry veya süre doldu diye kaynağı boş bırakma yoktur.

**Başarı kriteri:** yalnız geçerli görev/zaman kapsamında izinli erişim gönderilmiştir; reddedilen ve süresi geçmiş taleplerin komut dispatch sayısı sıfırdır. Erişim kararları, gönderimler ve bağımsız fiziksel sonuçlar audit'te ayrıdır; sağlayıcıda süre sınırının uygulandığı iddia ediliyorsa bunun ayrıca doğrulanmış kanıtı vardır.

### UC-03 — Birden fazla kapı ve binadan çıkış

**Aktörler:** robot, mevcut `CommandOwner`, görev/kaynak koordinatörü, her kapının yetkili erişim adaptörü ve gerektiğinde ayrı asansör entegrasyonu/operatörü.

**Önkoşullar:** bu genişleme pilot dışıdır. Her kapı için ayrı erişim hakkı, cihaz profili, feedback, açıklık/footprint ve clearance kanıtı gerekir. Çıkış yolu girişten önce planlanır; serbest çıkış, otomatik açılma, açık tutma veya yeniden kilitleme varsayılmaz. Rota kat değiştiriyorsa ayrıca doğrulanmış asansör erişimi/koordinasyonu gerekir; Unloc kapı erişimi bu bağımlılığı karşılamaz ve mevcut asansör simülasyonu saha kanıtı değildir.

**Tetikleyici:** görevin sonraki geçiş kaynağına ulaşması veya ortak teslimat alanından bina çıkışına dönmesi.

**Ana akış:**

1. Platform kapıları ve gerekiyorsa ayrı asansör kaynağını içeren izinli rotayı/çıkışı planlar; kaynak alma sırası mevcut koordinasyon politikasına uyar.
2. Her kapı için UC-02 yetki/zaman denetimi ve UC-01 fiziksel açılma doğrulaması yeniden uygulanır; ilk kapının ACK'i sonraki kapılar için izin değildir.
3. Tek `CommandOwner` geçişi yürütür. Önceki kaynak bütün robot/yük clearance'ı doğrulanana kadar tutulur; sıradaki kaynağın beklenmesi öncekinin boşaldığı anlamına gelmez.
4. Dönüşte çıkış kapısı için de doğrulanmış erişim/geçiş yöntemi uygulanır. Tam çıkış teyidi alınır; kapanma/yeniden kilitleme prosedürü varsa yalnız belgelenmiş yetenek ve bağımsız kanıtla değerlendirilir.

**Hata durumları:** bir kapının veya ayrı asansör bağımlılığının desteklenmemesi, zaman izninin bitmesi, sonraki kaynağın kullanılamaması veya çıkış teyidinin kaybında rota tamamlandı sayılmaz. Kapılar arasında tahmini açık tutma süresi veya doğrulanmamış genel unlock tekrarı kullanılmaz; UC-04 uygulanır.

**Başarı kriteri:** her geçişin yetki, açılma ve tam çıkış kanıtı kendi kaynağı/görev adımıyla ilişkilidir; binadan çıkış doğrulanmıştır. Prosedürün gerektirdiği kapanma/kilit sonucu kanıtsızsa bilinmeyen/operatör doğrulaması gereken durum olarak kalır; bütün bina erişimi normale döndü diye raporlanmaz.

### UC-04 — Hata yönetimi ve operatöre devir

**Aktörler:** robotun mevcut kontrol sahibi, platform görev/erişim koordinatörü, erişim/observation adaptörleri ve yetkili bina/robot operatörü.

**Önkoşullar:** site prosedürü hata/timeout, capability'ye bağlı bekleme/iptal, operatör rolü ve kontrol devri yöntemini tanımlar. Robotun son bilinen konumu ile kaynak doluluğu, görev durumundan ayrı tutulur; manuel hareket için de kontrol sahipliği uzlaştırılır.

**Tetikleyici:** yetki reddi, erişim API'si veya WAN kesintisi, kapının açılmaması, geçiş engeli, robot bağlantısının kaybı ya da çıkış kanıtının gelmemesi.

**Ana akış:**

1. Hata gönderim öncesi ret, gönderim sonrası sonucu bilinmeyen işlem veya fiziksel geçiş engeli olarak ayrılır; fiziksel başarı uydurulmaz.
2. Yetki reddi gönderim üretmez. API/WAN kaybı ya da ACK sonrası yanıtsızlıkta erişim sonucu uzlaştırılır; doğrulanmış destek yokken Bluetooth/offline alternatifine geçilmez.
3. Kapı açılmazsa veya açıklık engelliyse robot geçiş izni alamaz. Mevcut görevin bekleme/iptal tepkisi site prosedürü ve gerçek capability ile mevcut kontrol sahibinden yürütülür; gönderim ACK'i fiziksel duruş sayılmaz.
4. Robot veya çıkış feedback'i kaybolduysa kaynak doluluğu belirsiz tutulur; lease/timeout kaynağı serbest bırakmaz. Yetkili operatöre son kanıtlar, belirsizlik ve gereken doğrulama aktarılır.
5. Kontrol devri ve taze fiziksel durum doğrulandıktan sonra yetkili karar verilir: görev sonlandırma, onaylı manuel tamamlama veya yeniden yetkilendirilmiş devam. Operatörün mesajı aldım ACK'i, fiziksel gözlem/clearance kanıtı yerine geçmez.

**Hata durumları:** operatöre ulaşılamaması veya feedback'in hâlâ eski/güvenilmez olması otomatik devam ya da kaynak bırakma gerekçesi değildir. Belgelenmemiş tekrar açma, motoru zorla sürme, interlock bypass'ı veya paralel robot komut sahibi devreye sokulmaz.

**Başarı kriteri:** yetkisiz/tekrarlanan dispatch ve kanıtsız geçiş/kaynak bırakma oluşmadan hata kontrollü biçimde sonlandırılmış ya da açık operatör devrine alınmıştır. Teslimat ve fiziksel duruş/kapanma ayrıca kanıtlanmadıkça başarılı gösterilmez; yeniden devam için yeni geçerli karar ve gerekli taze durum bulunur.

### UC-05 — Bağımsız tesis olayının teslimat akışına etkisi

**Aktörler:** bağımsız `SourceListener`/tesis olay kaynağı, `FacilityAlarmResponse` politikası, etkilenen teslimat görevi/robot, mevcut `CommandOwner` ve tesis operatörü.

**Önkoşullar:** güvenilir kaynak, zone/site eşlemesi, onaylı tesis prosedürü ve robotun bildirim/eylem capability'leri tanımlıdır. Alarmın Unloc'tan geldiği veya Unloc kapı erişiminin yangın alarmı kaynağı olduğu varsayılmaz; kaynak ayrı doğrulanır. Mevcut sabit alarm simülasyonu bu saha politikasının yerine geçmez.

**Tetikleyici:** bağımsız kaynakta normalize edilen yangın alarmı/erişim kısıtlaması olayı veya aktif tesis durumu sırasında teslimat görevinin/robotun kapsama girmesi.

**Ana akış:**

1. Listener sıfır robot/görev varken de çalışır; olay veya güncel context, kaynak kalitesi ve aktivasyon kimliğiyle teslimat görevine ilişkilendirilir.
2. Etkilenen bina/bölge ve görev için politika, yalnız bildirim veya desteklenen bekleme/iptal etkisini seçer. Bildirim `RobotEventPort`, eylem yetkili `RobotCommandPort` sınırından değerlendirilir; RMF/vendor owner varsa ikinci hareket yolu açılmaz.
3. Bildirim teslimi ve komut ACK'i, robotun fiziksel tepkisinden ayrı kaydedilir; aynı aktivasyon/hedef/etki ikinci fiziksel komut üretmez. Kaynak ve geçiş doluluğu iptal kararıyla silinmez.
4. Kaynak durumu, robot sonucu ve operatör gereksinimi izlenir. Alarmın temizlenmesi, kaynağın reconnect olması veya olayın loglanması kendiliğinden erişim/`Resume` üretmez; devam ayrıca yetki ve fiziksel durum uzlaştırması ister.

**Hata durumları:** kaynak kaybı/`UNKNOWN`, eski boot/aktivasyondan clear veya güvenilmeyen olay yeni aktif alarmı temizlemez ve geçiş izni vermez. Desteklenmeyen bildirim/eylem başarılı sayılmaz; komut yeteneği yoksa sahte bekleme/iptal sonucu yerine açık sınır ve operatör ihtiyacı gösterilir.

**Başarı kriteri:** etkilenmiş teslimat için prosedüre uygun, audit'li karar verilmiştir; izin verilmeyen/unsupported eylemlerin komut dispatch sayısı sıfırdır. Olay kaydı/ACK fiziksel sonuç yerine kullanılmaz, sonuç kanıtsızsa belirsizlik görünür kalır ve hiçbir clear/reconnect otomatik devam ettirmez.
