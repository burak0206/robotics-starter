# Robot–bina entegrasyonu

Bu dosya **bugün uygulanan simülasyonları** ve **henüz uygulanmamış, planlanan case'leri** ayrı bölümlerde tanımlar. Ürün vizyonu, hedef mimari, güvenlik/komut yetkisi ve sürüm sınırlarının tek referansı [Ürün kapsamı ve teknik yön](product-scope.md) belgesidir. Buradaki mevcut durum akışları kimliği doğrulanmış robot görev yürütmesi veya saha doğrulaması değildir.

Amaç; robotların elektronik kapı, asansör, geçiş ışığı ve yangın alarmı gibi bina sistemleriyle ortak senaryolar üzerinden çalışmasını sağlamak. Ürün çekirdeği komutları, cihaz geri bildirimlerini ve robotun görev adımlarını birbirine bağlar.

Mevcut web arayüzü operatörün senaryoyu izlemesini ve simülasyonu denemesini sağlar. Kotlin backend REST sınırını ve istek doğrulamasını, Rust servisi gRPC üzerinden robot/cihaz bağlantısı sınırını sağlar. Mevcut sürümde süreç durumu bellekte tutulur.

| Bölüm | Destek durumu | Ne ifade eder? |
| --- | --- | --- |
| Aşağıdaki asansör, kapı ve sabit alarm iptal akışları | `SIMULATED` | Kodda çalışan, elle ilerletilen üç case; bu bölümlerdeki REST uçları mevcuttur |
| Planlanan tesis alarmı, erişim geçişi, yürüyen yol/merdiven, konveyör, devreye alma, Unloc teslimatı ve acil durum tatbikatı case'leri | `PLANNED` | Hedef davranış ve kabul koşulları; adaptör, yeni API veya saha desteği henüz yoktur |
| [Tek kapı güvenlik test planı](#door-security-tests) | `PLANNED` | İlk authn/authz geliştirmesinin kabul senaryoları ve issue taslakları; yeni test veya güvenlik mekanizması uygulanmamıştır |

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
| [`EmergencyCoordinationDrill`](#emergency-drill) | İki robot/bölgede alarm, geçiş kısıtı ve bağlantı kaybını birlikte değerlendir | Yalnız planlanan tatbikat; mevcut sabit alarm demo'su veya gerçek afet uygunluğu değildir |

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

<a id="emergency-drill"></a>

## `EmergencyCoordinationDrill`: iki robotlu acil durum tatbikatı — `PLANNED`

Bu, ilk teknik tatbikat adayıdır; **bu görevde simülatör, test veya uygulama eklenmemiştir**. Mevcut elle ilerletilen sabit alarm demo'sunun bu senaryoyu çalıştırdığı iddia edilmez. Ürün/sorumluluk/AI sınırları [acil durum robot koordinasyonu](product-scope.md#emergency-robot-coordination) bölümünde tutulur; olay ve tepki hattı mevcut `FacilityAlarmResponse` akışıdır. Gerçek afette kullanılabilirlik veya bir robotun tehlikeli ortama uygunluğu simülasyon sonucundan çıkarılamaz.

**Aktörler:** tatbikat operatörü, simüle tesis olay kaynağı/listener, platform görev/erişim/kaynak koordinasyonu, iki simüle robot ve mevcut tekil `CommandOwner`'ları, yetkili tesis olay sorumlusu.

**Önkoşullar:** tek test tesisinde `zone-a` ve `zone-b`, ortak `passage-01`, iki robotun görev/rezervasyonları ve farklı bildirim/eylem capability'leri tanımlıdır. Robot A yaklaşma noktasındadır; robot B'nin son doğrulanmış konumu geçiş kaynağındadır ve çıkışı henüz teyit edilmemiştir. Alarmın etkilediği bölge, destekli tepkiler, onaylı bekleme/devralma ve normale dönüş koşulları açıkça `DemoSiteProcedure` fixture'ıdır; saha prosedürü değildir. Kontrollü saat ve source/robot feedback fixture'ları planlanır; gerçek erişim adaptörüne komut gönderilmez.

**Tetikleyici:** normal simüle görevler sürerken bir alarm aktivasyonu, ortak geçişin kullanılamaz hale gelmesi ve robot B bağlantısının kaybı enjekte edilir. Geçiş kısıtı test verisidir; robot eşikteyken kapıyı fiziksel kapatma talimatı değildir.

**Ana akış:**

1. Listener alarmı kaynak/aktivasyon/bölge/zaman/kalitesiyle kaydeder. Platform etkilenen robot, görev, queued hedef ve kaynakları mevcut sürümlü harita/politikayla ilişkilendirir; kaynak kaybını alarm clear saymaz.
2. Test prosedürüne göre kısıtlanan bölge/geçişe yeni normal admission ve eski rotaya bağlı queued hedef dispatch'i kapanır. Devam eden görevde hangi destekli etkinin seçildiği ayrıca kaydedilir; bütün robotlara aynı dur/hareket talimatı verilmez.
3. Robot A için prosedürün seçtiği destekli tepki mevcut kontrol sahibinden istenir. Olay gönderimi, receipt/command ACK ve ayrı simüle davranış feedback'i ayrı ilerletilir; ACK olup sonuç gelmeyen varyant da denenir.
4. Robot B'nin bağlantısı/telemetrisi ve çıkış kanıtı kaybolur. Son konum zamanıyla gösterilir; güncel konum/duruş uydurulmaz. Sonuç belirsiz, geçiş doluluğu `UNCONFIRMED` ve operatör ihtiyacı görünür kalır; timeout/lease expiry kaynağı boşaltmaz.
5. Alarm clear ve robot reconnect ayrı enjekte edilir. Bunlar otomatik `Resume`, rota açma veya expired komut replay'i üretmez. Geçişi bir fiziksel etkiyle açmadan önce de aynı yetki/kanıt kuralları geçerlidir.
6. Yetkili tesis rolü ilgili bölge/rota için normale dönüş kanıtını onaylar; robot mode/pose, kaynak clearance, güncel kısıtlar, policy ve authority uzlaştırılır. Yalnız yeni geçerli kararla normal göreve dönüş değerlendirilir; eski olay onayı başka aktivasyonun kısıtını kaldıramaz.
7. Olay, robot başına karar/kanıt, belirsizlik ve operatör adımları audit'e bağlanır. Olay analizi/replay yalnız kayıt veya izole simülasyon girdisidir; üretim komut adaptörüne bağlanmaz.

**Hata durumları:** yetkisiz normalleştirme/geri dönüş isteği; yanlış site/zone eşlemesi; stale konum/harita; duplicate alarm veya eski clear; desteklenmeyen tepki; ACK sonrası sonuç kaybı; robot bağlantısı ve çıkış teyidi kaybı; operatöre ulaşılamaması. Kısıtı uygulayamayan adaptöre ilgili yeni görev verilmez. Yetkisiz/unsupported dispatch, kör fiziksel retry, ikinci motion owner ve kısıt/alan boşluğu kanıtsız otomatik devam yoktur.

**Başarı kriteri:** simüle olaydan etkilenen her robot/görev/kaynak için karar, kanıt aşaması ve belirsizlik görünürdür. Kısıtlı rotaya queued dispatch, yetkisiz/unsupported dispatch, duplicate effect ve expired hareket replay sayıları sıfırdır. ACK tek başına doğrulanmış tepki üretmez; kayıp çıkışta kaynak tutulur. Clear/reconnect sonrasında yetkili normale dönüş kararı ve gerekli taze uzlaştırma olmadan normal admission/Resume açılmaz; manuel kontrol de tek kontrol sahibi modelini korur.

**Ölçümler:** güvenilir kaynak zamanı mevcutsa olaydan listener alımına kadar algılama; yerel alımdan policy kararına; dispatch'ten receipt/ACK'e ve ayrı doğrulanmış davranışa süreler ölçülür. Ayrıca cevapsız robot sayısı, doğrulanamayan konum/sonuçlar, operatöre devir ve müdahale süresi kaydedilir. Saat/kaynak kanıtı yoksa ilgili ölçüm bilinmiyor kalır; ACK süresi tepki süresi yerine kullanılmaz. Sayısal kabul eşikleri daha sonra kararlaştırılacak; simülasyon ölçümleri saha hizmet garantisi değildir.

<a id="door-security-tests"></a>

## İlk kimlik ve yetki geliştirmesi: tek kapı güvenlik test planı — `PLANNED`

**İnceleme: 2026-11-16, GitHub `main` commit `ed6c564113bcbcd5d43bab0ce21d3942af98a168`.** README, ürün kapsamı, controller/gateway, proto ve test kaynakları karşılaştırıldı. Kaynakta 22 Rust ve 23 Kotlin test tanımı var; bu görevde testler çalıştırılmadı. Aşağıdakiler yeni test kodu veya tamamlanmış güvenlik özelliği değil, uygulanabilir kabul senaryoları ve GitHub issue taslaklarıdır.

### Bugünkü kanıt ve hedef sözleşme

- [DoorController](../backend/src/main/kotlin/dev/robotics/DoorController.kt) doğrudan `RobotGateway` çağırır; [build.gradle.kts](../backend/build.gradle.kts) içinde Spring Security/security-test bağımlılığı yoktur. [Gateway](../backend/src/main/kotlin/dev/robotics/RobotGateway.kt) `usePlaintext()` kullanır; [Rust servis kurulumu](../robot-agent/src/main.rs) TLS/auth interceptor eklemez. `passageAllowed`, simüle kapının workflow durumudur; çağıran robotun erişim yetkisi değildir.
- [Proto](../proto/robot.proto) içinde `RequestDoorPassageRequest`/`StartDoorPassageRequest` boştur; `AdvanceDoorSimulationRequest` yalnız `expected_state` taşır. Robot/tenant/site/task/target/grant context'i yoktur. `door-01` Rust yanıtında sabittir. Yeni kaynak yetkisi testi mevcut olmayan `/door-02` endpoint'i veya desteklenmeyen `doorId` gövdesi varsaymaz.
- **Mevcut hata eşlemesi:** kapı girdi hatası `400`, durum/alarm precondition hatası `409`, downstream unavailable `503`, deadline `504`, diğer RPC hataları `502`. [DoorErrorHandler](../backend/src/main/kotlin/dev/robotics/DoorErrorHandler.kt) içindeki bu kodlar auth denetimi değildir. **Önerilen yeni REST sözleşmesi:** eksik/geçersiz kimlik `401`; doğrulanmış kimlik + policy reddi `403`. Bunlar henüz uygulanmamıştır.
- **Önerilen gRPC sözleşmesi:** eksik/geçersiz servis kimliği veya güvenilmeyen context `UNAUTHENTICATED`; doğrulanmış kimliğin kapsam dışı işlemi `PERMISSION_DENIED`. Bu güvenlik sınırı da henüz yoktur. Backend servis kimliği, robot kimliğinden ayrılır. İç servis kimlik hatası otomatik olarak son kullanıcıya ait `401/403` sayılmaz; mevcut REST handler bu RPC kodlarını `502` dalına düşürür, yeni eşleme ayrıca kararlaştırılmalıdır.

### Ortak fixture ve ölçüm noktaları

Çalışma varsayımı: tek `robot-01`, tek `door-01`, test amaçlı `tenant-a/site-a/task-01`; alarm başlangıçta pasif. Tenant/site/task modelinin mevcut kodda olduğu iddia edilmez; ilk test fixture'ı bunları güvenilir server-side binding ve aktif görev kaydıyla temsil edecektir. Negatif principal veya başka kaynak adı ikinci çalışan robot/kapı kurmaz. Robot yalnız kapı okuma, geçiş isteme ve başlatma yetkilerine adaydır; simülasyon ilerletme ve alarm yönetimi ayrı `OperatorPrincipal` test kimliğindedir. İşlem adları ve kimlik taşıma sözleşmesi geliştirme öncesinde netleştirilecek; yeni HTTP yolu önerilmemektedir.

Fixture açıkça `ROBOT_MODE=simulation`, izole loopback/ephemeral port ve yalnız test simülatörlerini kullanmalıdır; çalışan bir ROS veya saha servisine bağlanmaz. Güvenlik garantisi yalnız aşağıda listelenen door/alarm yollarına aittir. Aynı agent'ın diğer Robot/Elevator servisleri korunmuş sayılmaz; test/security profile'ında dış erişime kapalı veya deny-by-default tutulur. Özellikle mevcut `RobotService.Stop` ROS 2 modunda gerçek yayın yoludur; bu test planı onu güvenli hâle getirmiş sayılmaz ve o yola komut göndermez.

İmzalı test JWT'si, mevcut Spring yığınına uygun ilk authn adayıdır; seçilmiş üretim IdP'si değildir. Gerçek imza, izinli algoritma/issuer/audience ve kontrollü saatle expiry doğrulaması planlanır. `ActorContext` yalnız doğrulanmış kimlikten üretilir. Serbest body/header/metadata beyanı policy veya server-side kaynak binding'ini değiştiremez. Kullanılacak test anahtarları ve role/scope modeli uygulama işinde seçilecek; bu görevde anahtar veya credential oluşturulmaz.

Kotlin'de yöntem başına `rpcCallCount`; Rust'ta yetki gate'inden sonraki simulator çağrı sayısı ve semantic snapshot ölçülür. Snapshot karşılaştırması her okumada yenilenen `observedAtUnixMs` alanını hariç tutar; bu zaman gerçek sensör tazeliği kanıtı değildir. REST auth reddinde **RPC sayısı sıfırdır**. Doğrudan gRPC red testinde ağ çağrısı yapılır, fakat simulator invocation/mutation sıfır ve durum aynıdır. Test decision recorder/spy, ret/izin nedenini ve actor/resource/action eşlemesini gözler; **kalıcı audit değildir**.

Retlerin yanlış domain durumundan kaynaklanmaması için her işlem ayrı hazırlanır: `request-passage` için `IDLE`, `start-passage` için `READY_TO_PASS`, simulation advance için `OPENING`. Auth ret testleri geçerli iş gövdesiyle çalışır. Tüm yeni güvenlik beklentileri aşağıda **planlanmıştır**; bugünkü alarm/durum davranışlarının korunması ayrıca belirtilir.

### Senaryolar ve iki sınırdaki doğrulamalar

| ID / önkoşul | Beklenen davranış | Kotlin REST doğrulaması | Rust/gRPC doğrulaması |
| --- | --- | --- | --- |
| `SEC-01` — Authorization yok; kapı işlem için uygun durumda | Kimlik doğrulanmadan kapı okunamaz veya komut verilemez | Korunan `/api/door/status`, `/request-passage`, `/start-passage` için hedef `401`; controller/gateway çağrısı ve RPC sayısı `0` | Backend'den çağrı gelmez; kapı aynı kalır. Doğrudan kimliksiz RPC ayrıca `SEC-10`'da sınanır |
| `SEC-02` — Bozuk token, geçersiz imza, yanlış issuer/audience veya süresi dolmuş token | Her varyant kimlik reddidir; payload içindeki doğru robot adı yetki sağlamaz | Gerçek filter chain ve gerçek imzalı/bozulmuş token kullan; hedef `401`, RPC `0`; secret/decoder ayrıntısı yanıt gövdesine sızmaz | Simulator invocation yok; ağ bağlantı hatasıyla auth hatası birbirine karıştırılmaz |
| `SEC-03` — Geçerli robot kimliği; izin kümesi `door-01`'i kapsamıyor, yanlış site/tenant/robot binding'i veya işlem izni eksik | Varsayılan ret; başka kaynak izni bu kapıya taşınmaz | Server-resolved hedef `door-01`; izin fixture'ı boş veya yalnız başka kaynak için. Hedef `403`, RPC `0`; ikinci kapı/endpoint eklenmez | Kapı değişmez; yanlış scope taşıyan doğrudan RPC, planlanan guard ile mutasyon öncesi reddedilir |
| `SEC-04` — Geçerli fakat yetkisiz kimlik; sahte robot/site/role header'ı veya body alanı gönderiliyor | İstemci beyanı doğrulanmış principal'ı yükseltemez | Server binding/verified context değişmez; hedef `403`, RPC `0`. Desteklenmeyen body alanı mevcut bir hedef seçme API'si gibi ele alınmaz | Backend adına doğrulanmamış actor/grant metadata'sı güvenilir olmaz; sahte context ret üretir |
| `SEC-05` — İzinli `robot-01`, aktif test görevi, alarm pasif, kapı `IDLE` | Yetkili istek yalnız açılma akışını başlatır; fiziksel geçiş başarısı ilan etmez | `request-passage` tek RPC; mevcut yanıt sözleşmesi `200/OPENING`, `doorOpen=false`, `passageAllowed=false`. Yetkili fixture/operator açılma olayını verdikten ve `SEC-06` kontrollerinden sonra `start-passage` tek RPC ile `PASSING` olur | Gerçek Rust simülatöründe `IDLE → OPENING → READY_TO_PASS → PASSING → CLOSING → COMPLETED`; açık/çıkış/kapanma olayları ayrı. Robot hareketi ve fiziksel sensör sonucu iddia edilmez |
| `SEC-06` — Kapı açılmış; başlangıç isteğinden sonra görev/grant iptal edilmiş, süresi bitmiş veya binding değişmiş. Ayrı varyant: henüz `OPENING` | Açık kapı ve önceki izin yeni yetki yerine geçmez; her start yeniden doğrulanır | Geçerli kimlik + iptal/expired grant için hedef `403`, yeni start RPC `0`; tokenın kendisi expired ise `401`. Server-side pending passage robot/task/door bağına bakılır. Yetki geçerli ama kapı açılmamışsa mevcut `409` korunur | Yetkili fakat erken start mevcut `FAILED_PRECONDITION`, durum değişmez. Güvenlik reddi mutasyon öncesidir; geçerli binding `start`ı sahiplenmeden başka task başlayamaz |
| `SEC-07` — İzinli robot; alarm aktif. Ayrı fixture'lar: boşta kapı ve devam eden geçiş | Yeni geçiş/start engellenir; devam eden demo akışı iptal olur, son sensör/doluluk korunur | Mevcut Rust alarm gate'ine ulaşan çağrı için `409`; bu durumda RPC `1` olabilir, simulator domain mutation `0`. Kimlik/yetki reddi ise yine `401/403`, RPC `0` olmalıdır | Mevcut `FAILED_PRECONDITION`; iptal edilen akışta `CANCELLED/recoveryRequired`, `passageAllowed=false`. Boşta kapı alarm yüzünden otomatik `CANCELLED` olmaz; eşik doluluğu uydurulmaz |
| `SEC-08` — Kotlin yetki kontrolünden sonra, RPC işlenirken alarm aktivasyonu yarışıyor | Rust son alarm gate'i korunur; REST tarafındaki eski alarm okumasına güvenilmez | İzinli çağrı için güvenlik kontrolü yapıldığı kaydedilir; alarm ret sonucu `409`a taşınır. Her yarışın kesin ret olması istenmez | Mevcut ortak gate sıralaması: komut aktivasyondan önce kabul edilebilir veya sonra reddedilir. Aktivasyon tamamlandıktan sonraki komutlar ilerlemez; auth wrapper gate'i baypas etmez |
| `SEC-09` — Robot kimliğiyle simulation advance veya alarm activate/clear/reset deneniyor; sonra yetkili operator varyantı | Robot sahte kapı feedback'i üretemez veya alarmı temizleyip geçişi açamaz | `/api/door/simulation/advance` ve `/api/fire-alarm/simulation/{activate,clear,reset-cancelled}` ayrı ayrı hedef `403`, RPC `0`; anonim için `401`. İzinli operator yalnız test profile'ında ilerletir; alarm aktifken reset mevcut `409` | Aynı işlem rolleri doğrudan gRPC'de de korunur. Clear iptal edilen kapıyı devam ettirmez; simulation reset fiziksel clearance sayılmaz |
| `SEC-10` — REST atlanarak gerçek loopback gRPC çağrısı; eksik/geçersiz servis kimliği, sahte/uygunsuz delegation veya doğru servis kimliği varyantları | REST koruması erişilebilir agent portunda atlanamaz | Başarılı REST fixture'ı yalnız atanmış backend servis kimliği ve sınırlandırılmış context gönderir; robot token'ı otomatik servis kimliği yapılmaz | `main` ile aynı korumalı servis kurulumu üzerinden gerçek transport testi. Hedef authn red `UNAUTHENTICATED`, doğrulanmış fakat kapsam dışı işlem `PERMISSION_DENIED`; simulator invocation `0`, snapshot aynı. Doğru service + robot/task/resource/action context'i normal gate'lere ulaşır |
| `SEC-11` — Kimlik/policy doğrulanamıyor veya backend–agent bağlantısı kesiliyor; ayrı varyant: komut gönderildi, yanıt kayboldu | Kontrol başarısızlığı izin değildir; gönderim sonrası timeout fiziksel/sanal sonucun bilinmediği durumdur | Auth/policy dependency hatasında dispatch `0`, yanıltıcı başarı yok; teknik HTTP kodu bu bağımlılık tasarlanırken seçilir. Mevcut transport `503/504` korunur, auth retlerine çevrilmez. Mutation RPC için timeout testinde tek deneme ve otomatik retry yok | İstek ulaşmadıysa mutasyon yok; ulaşıp yanıt kaybolduysa durum değişmiş olabilir. `OUTCOME_UNKNOWN` hedef davranıştır; mevcut uygulamada kalıcı uzlaştırma/journal olduğu varsayılmaz |

### Mevcut testlerden yeniden kullanılacak parçalar

| Kaynak | Gerçek test adı / kullanılacak parça | Sağlamadığı kanıt |
| --- | --- | --- |
| [DoorApiTest](../backend/src/test/kotlin/dev/robotics/DoorApiTest.kt) | `passage commands and expected simulation state reach distinct RPCs`: yöntem başına sayaç. `missing null blank nonstring and malformed event bodies never reach RPC`: sıfır RPC assertion'ı. `status maps door feedback and permission separately`: DTO ayrımı | Kimlik/yetki kontrolü veya gerçek Rust agent akışı |
| Aynı Kotlin dosyası | `unknown state maps to 400 and stale state or premature passage maps to 409`; `unavailable door service has a scoped 503 response`; `unresponsive door service hits the actual client deadline` | Son iki test yalnız status RPC'sini sınar; mutation timeout/retry/unknown outcome için yeni fixture gerekir |
| [FireAlarmApiTest](../backend/src/test/kotlin/dev/robotics/FireAlarmApiTest.kt) | `four routes reach distinct RPCs and clear retains cancelled recovery feedback`; `cancelled door and elevator DTOs preserve sensor feedback and alarm flags`; `reset blocked by active alarm returns scoped 409 without exposing internals` | Operator rol kontrolü veya gerçek Rust alarm gate'i; ilk kapsamda yalnız kapı assertion'ları yeniden kullanılır |
| [door.rs](../robot-agent/src/door.rs) | `passage_requires_opening_then_clearing_before_closure_and_can_be_replayed`; `out_of_order_commands_do_not_mutate_or_close_an_occupied_doorway`; `missing_unknown_or_stale_events_never_advance` | Actor/grant bağlama, kalıcı idempotency veya gerçek fiziksel sonuç |
| Aynı Rust dosyası | `simultaneous_requests_accept_exactly_one`; `duplicate_opening_feedback_cannot_also_start_passage`; `duplicate_clearance_feedback_cannot_also_confirm_closed` | Çok robotlu rezervasyon veya restart sonrası deduplication; aynı robotun yarışan istekleri olarak korunabilir |
| [fire_alarm.rs](../robot-agent/src/fire_alarm.rs) | `alarm_cancels_both_cases_without_inventing_sensor_feedback`; `idle_and_completed_cases_are_blocked_but_not_cancelled`; `reset_only_changes_cancelled_cases_and_preserves_other_active_work`; `activation_racing_commands_serializes_on_the_same_gate` | Servis/robot kimliği ve yetkili alarm yönetimi; asansör assertion'ları mevcut regresyonda kalır, yeni auth kapsamı kapıyla sınırlıdır |

Kotlin fixture'ları `MockMvcBuilders.standaloneSetup` kullanır; gelecekte eklenecek `SecurityFilterChain` burada otomatik çalışmaz. Yeni authn testleri gerçek security config ve token decoder içeren `@SpringBootTest` + `@AutoConfigureMockMvc` veya config'in açıkça dahil edildiği MVC slice kullanmalıdır. Mevcut loopback gRPC double/sayaçlar bu fixture'a taşınabilir. `@WithMockUser` veya `jwt()` postprocessor authz birim testine yardımcı olabilir; gerçek imza/issuer/audience/expiry doğrulamasını kanıtlamaz.

Mevcut Rust testleri domain/trait metodunu doğrudan çağırır; sonradan eklenecek interceptor'ı test etmiş sayılmaz. `SEC-10` için üretim koduyla aynı servis kurulumunu kullanan ephemeral loopback server ve gerçek gRPC client gerekir. [build.rs](../robot-agent/build.rs) client üretimini kapatır; testte mevcut Java stub'ları veya küçük tonic client kullanma seçimi ayrı uygulama kararıdır. Bu seçim şu görevde bağımlılık/build değişikliği oluşturmaz.

### Kısa GitHub issue taslakları

Aşağıdaki başlık/açıklamalar yayıma hazır taslaktır; bu görevde GitHub issue açılmadı. Sıra önerisi `I1 → I2 → I3`; `I4/I5` tamamlanmadan listelenen door/alarm yollarının korunduğu söylenmez, `I6` bu kapsamın birleşik kabul kapısıdır. Diğer REST/gRPC servisleri bu güvenlik iddiasının dışındadır.

| ID / issue başlığı | Kısa açıklama ve bitiş kriteri |
| --- | --- |
| `I1 — Validate robot identity at the door REST boundary` | Test issuer/anahtarıyla doğrulanmış signed identity'den `ActorContext` üret; gerçek filter chain fixture'ını ekle. `SEC-01/02`: eksik/geçersiz kimlik hedef `401`, tüm retlerde RPC `0`; issuer/audience/algoritma/expiry ve güvenli hata gövdesi doğrulansın. Üretim IdP/mTLS eklenmiş sayılmasın |
| `I2 — Authorize server-resolved door operations` | `AccessPolicy` + `DoorCommandPort` sınırında doğrulanmış robot/site/task/door/action eşlemesi uygula. `SEC-03/04`, izinli request bölümü `SEC-05` ve lookup failure bölümü `SEC-11`: izinli `door-01` isteği bir, ret sıfır dispatch; policy reddinde hedef `403`, lookup/dependency hatasının teknik HTTP kodu ayrıca seçilir. Test decision spy'ı kullan; yeni `doorId` HTTP alanı varsayma, security bağımlılıkları bu işlerde ayrıca seçilsin |
| `I3 — Recheck authorization before starting a passage` | Pending passage'ı server-side robot/task/door binding'ine bağla, start anında güncel görev/grant ve tokenı yeniden doğrula. `SEC-06` ve tamamlanan `SEC-05`: iptal/expired/mismatched bağda start dispatch `0`, erken start mevcut `409`. Context taşıma biçimini kararlaştır; bellek içi fixture restart dayanıklılığı değildir |
| `I4 — Restrict simulation and alarm management routes` | Eski request/start yolları ve simulation/alarm kontrol yolları için role/action matrisi uygula; robot test feedback'i üretemesin veya clear/reset yapamasın. `SEC-09`: robot/anonim retleri sıfır RPC, izinli operator kontrollü fixture, aktif alarm reset reddi ve clear sonrası recovery korunsun. Henüz korunmayan yol deny-by-default veya kapalı development profile'ında kalsın |
| `I5 — Guard direct gRPC door and alarm commands` | Backend `ServicePrincipal` ile robot delegation'ını ayır; kimlikli ve süre/kapsam bağlı komut context'inin metadata/proto contract'ını belirle. `main` ile aynı server factory üzerinde `SEC-10`: kimlik/context sahteciliği ve yanlış scope mutasyon öncesi reddedilsin; servis credential'ı tek başına bütün robot/kaynaklar için grant olmasın. İzole test transport'u üretim mTLS değildir |
| `I6 — Verify authorized door flow and alarm precedence end to end` | Gerçek Kotlin security chain → gRPC transport → Rust simulator ile `SEC-05/07/08` ve transport kısmı `SEC-11` için kapı odaklı kabul fixture'ı ekle. Alarm gate, son sensör durumu, ACK/açılma ayrımı ve timeout sonrası tek deneme korunsun; mevcut 45 kaynak testi regresyon olarak çalıştırılsın. Çalıştırılan sonuçlar ayrıca raporlansın |

İlk küçük PR yalnız request kimliği/yetkisini tamamlıyorsa `start`, simulation ve alarm yolları anonim alternatif olarak açık bırakılamaz; bunlar korunana kadar normal kullanımda reddedilir. Kalıcı audit, dayanıklı command journal/idempotency, restart reconciliation, üretim PKI/mTLS, gerçek cihaz bağlantısı ve robot navigasyonu bu test alt planıyla tamamlanmaz. [P1.1/P1'in mevcut dayanıklı kayıt ve diğer kabul kriterleri](product-scope.md) korunur; test spy'ının çalışması bunları karşılamaz. Unloc, asansör veya afet robotu entegrasyonu bu ilk güvenlik kapsamına eklenmez.
