# Dream Displays 1.10.4 + Kirazium VTT

This branch starts from the original author's `v1.10.4` release,
commit `0d13cb865ac4d4d6783f556b6e8130291056ccef`. It carries only the
Kirazium external-WebVTT and personal subtitle settings features. The old
Android decoder/native patches, media catalogue, custom MP4 origin, and
blanket Kirazium management permission override are not carried forward.

## Altyazı kullanımı

Yapımcının video sağlayıcısından gelen altyazıları korunur. CC menüsünden
videonun altyazısını, sunucunun tanımladığı **Haricî WebVTT** kaynağını veya
**Kapalı** seçeneğini seçebilirsin. Aynı anda tek altyazı katmanı çizilir.
Seçimin yalnızca seni etkiler ve istemcide ekran başına kaydedilir.

Ayrı altyazı ayarları menüsü şunları korur:

- Açma/kapatma, boyut ve dikey konum.
- Default/uniform yazı tipi seçimi.
- Yazı, kenarlık ve arka plan rengi; arka plan saydamlığı.
- Önizleme, renk seçici ve görünümü sıfırlama.

Düz ekranlarda Minecraft yazı tipi çizimi kullanılır. Kıvrımlı ekranlarda
yapımcının yüzeye uyan doku çizimi korunur; renk/boyut/konum ayarları uygulanır,
ancak yazı tipleri AWT'nin mantıksal Sans/Monospaced eşlemesidir.

## Sunucu komutları

Ekrana bakarak `this` ya da ekran kimliğini kullan:

```text
/display subtitle this url https://example.com/altyazi.vtt
/display subtitle this file subtitles/altyazi.vtt
/display subtitle this off
```

Komutlar yapımcının video değiştirme izinlerine, ekran sahibi/bölge erişim
kurallarına ve haricî kaynak politikasına uyar. Dosya seçeneği yalnızca
DreamDisplays'in `media` dizini içindeki `.vtt` dosyaları içindir. İsteğe bağlı
yerel VTT sunucusu `media-server.properties` ile açılır; izleyicilerin
`public-base-url` adresine erişebilmesi gerekir. Bu servis video dosyası sunmaz.
Yerel dosyalar en çok 2 MiB; istemcinin indirdiği VTT en çok 4 MiB olabilir.
Video değiştiğinde eski videoya bağlı haricî VTT temizlenir.

## Geçiş

Eski Kirazium veritabanındaki `subtitleUrl`, yapımcının mevcut `customData`
alanına aktarılır. Yeni bir VTT kaynağı varsa üzerine yazılmaz. Başarıyla
taşınan eski alan temizlenir; geçersiz/eski kaynaklar inceleme için korunur.
Aktarım tekrar çalıştırıldığında ikinci bir değişiklik oluşturmaz.

Yeni bir protobuf alanı eklenmez. Haricî kaynak
`dreamdisplays:subtitle_vtt_url` anahtarıyla taşınır; yapımcının kendi altyazı
protokolü değiştirilmez.

Sunucu ve istemci birlikte yükseltilmelidir. Eski özel 1.9.5 istemcisi protobuf
alan 20'yi `subtitleUrl` için kullanırken orijinal 1.10.4 bu alanı `access`
için kullanır. Eski istemciyle yeni sunucunun uyumlu olduğu varsayılamaz.
Paper çıktısı sunucudaki 26.3 için; Fabric çıktısı mevcut launcher'ın
Minecraft 1.21.11 hedefi için hazırlanır. Eski Android native yamaları bu
çalışmanın kapsamına dahil değildir.

## Doğrulama

Odaklı testler: kişisel ayar sınırları, Türkçe/UTF-8 WebVTT, çakışan cue'lar,
ileri/geri zaman seçimi, kaynak metadata'sı ve bellek içi SQLite geçişi.
Native kütüphaneler değiştirilmez; resmî 1.10.4 dağıtımından aynen kullanılır.
Derleme/test başarısı gerçek istemci video/altyazı görüntüsünün doğrulanması
anlamına gelmez; oyun içinde kaynak seçimi, menü, senkronizasyon ve kıvrımlı
ekran görünümü ayrıca kontrol edilmelidir.

Windows'ta resmî native paketini kullanarak yerel derleme yapılırsa, yalnızca
büyük/küçük harfle ayrılan lisans dosyalarının kaybolmasını önlemek için son
Fabric JAR'ına aşağıdaki aracı uygula. Araç yalnızca resmî native girdilerini
aktarır ve uygulama sınıflarının değişmediğini doğrular:

```text
python tools/restore_release_natives.py original-1.10.4-fabric.jar built-fabric.jar
```
