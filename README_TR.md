# Meteor Camera – Samsung Galaxy A17 5G

Bu ilk sürüm, Camera2 API üzerinden cihazın izin verdiği manuel sensör pozlamasını
kullanmayı dener. Kullanıcıdan gelen Camera2 çıktısında arka Camera ID 0 için:

- Hardware Support Level: LIMITED
- Manual exposure: 1
- Manual focus: 1
- RawCapture: 1
- BurstCapture: 1

görülüyor.

ÖNEMLİ:
Gönderilen çıktıda SENSOR_INFO_EXPOSURE_TIME_RANGE değeri yer almıyor. Bu nedenle
30 saniyenin donanım/HAL tarafından kabul edileceği önceden garanti edilemez.
Uygulama gerçek pozlama aralığını açılışta okur ve istenen süreyi desteklenen aralığa
sıkıştırır. Aralık okunamıyorsa istek doğrudan gönderilir; cihaz reddederse durum
mesajı gösterilir.

## Özellikler

- 5 saniye geri sayım
- 5 / 10 / 15 / 20 / 25 / 30 saniye pozlama seçimi
- ISO 400 / 800 / 1600 / 3200 / 6400
- Manuel odak: sonsuz (0.0f)
- AE kapalı, manuel sensör pozlaması
- AWB kilidi
- OIS'i kapatma denemesi
- JPEG kaydı: Pictures/MeteorCamera
- Arka arkaya çekim
- Cihazın desteklediği gerçek pozlama aralığını ekranda gösterme

## Android Studio ile açma

1. Android Studio'yu güncel bir sürümle aç.
2. File > Open seç.
3. Bu klasörü seç: MeteorCamera
4. Gradle senkronizasyonunun tamamlanmasını bekle.
5. Telefonu USB ile bağla.
6. Telefonda Geliştirici seçenekleri > USB hata ayıklama açık olmalı.
7. Android Studio'da cihaz olarak Galaxy A17'yi seç.
8. Run ▶ ile yükle.

## APK oluşturma

Android Studio:
Build > Build App Bundle(s) / APK(s) > Build APK(s)

APK genellikle:
app/build/outputs/apk/debug/app-debug.apk

altında oluşur.

## Telefona kurma

APK'yı telefona aktar, dosyaya dokun ve Android'in istediği şekilde
bilinmeyen kaynaktan uygulama yükleme iznini ver.

## İlk test

Telefonu sağlam bir tripoda koy.

1. 5 sn seç.
2. ISO 800 veya 1600 seç.
3. Arka kamerayı gökyüzüne çevir.
4. Çek.
5. Fotoğrafın gerçekten yaklaşık 5 saniyelik pozlama olup olmadığını kontrol et.
6. Daha sonra 10, 20 ve 30 saniyeyi ayrı ayrı dene.

Eğer 30 saniye isteği reddedilirse uygulama bunu log/status mesajında gösterecek.
Bu durumda bir sonraki sürümde A17'nin gerçek sınırına göre daha güvenli bir çözüm
tasarlayabiliriz.

## Meteor çekimi için başlangıç ayarı

Karanlık gökyüzünde başlangıç olarak:
- 20–30 sn
- ISO 800–1600
- Sonsuz odak
- Tripod
- Arka arkaya çekim

denenebilir.

Ay/şehir ışığı fazlaysa ISO'yu düşürmek veya pozlamayı kısaltmak gerekir.

## Not

Bu sürüm özellikle "tek 20–30 saniyelik gerçek sensör pozlaması" hedefiyle
hazırlanmıştır. Yazılımla sensörün desteklemediği bir pozlama süresi zorlanamaz.
Ayrıca bu sürüm RAW/DNG menüsünü henüz kullanıcı arayüzüne koymuyor; Camera2 çıktısında
RawCapture görünse de Samsung'un RAW akışının pratikte çalıştığını ayrıca test etmek
gerekir.
