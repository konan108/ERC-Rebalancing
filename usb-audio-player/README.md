# HiRes USB Player

Android-Musikplayer für **USB-DACs** – inspiriert von *USB Audio Player PRO*, aber
auf Basis der offiziellen Bit-Perfect-API von Android 14 statt eines eigenen USB-Treibers.

## Funktionen

| Bereich | Umsetzung |
|---|---|
| Bibliothek | Liest lokale Musik über MediaStore (FLAC, WAV, ALAC, MP3, AAC, Opus …), Suche nach Titel, Interpret und Album |
| Wiedergabe | Media3/ExoPlayer, Hintergrundwiedergabe, Benachrichtigung, Kopfhörer- und Bluetooth-Tasten, Zufall/Wiederholen |
| **Bit-Perfect** | Android 14+: `AudioManager.setPreferredMixerAttributes` mit `MIXER_BEHAVIOR_BIT_PERFECT` für den USB-DAC, Track für Track passend zum Dateiformat |
| **Native Abtastrate** | Ist Bit-Perfect nicht möglich, läuft der USB-Mixer mit der Abtastrate der Datei (44,1/88,2/96/192 kHz …) statt alles auf 48 kHz zu resampeln |
| **Signalpfad-Anzeige** | Quelle → Decoder → DSP → Ausgabe-PCM → Mixer → Gerät, farbcodiert (grün = bit-perfect, blau = native Rate) |
| **Parametrischer EQ** | 10 Bänder (Peak, Low-/High-Shelf), Frequenz/Gain/Q je Band, Preamp, Presets, Frequenzgangkurve; 64-bit-Biquads |
| DAC-Info | Name, Abtastraten, Formate, Kanäle und die vom DAC angebotenen Bit-Perfect-Formate |

## TIDAL

Der Tab **TIDAL** verbindet dein TIDAL-Konto über die offizielle TIDAL-API
(OAuth 2.0 mit PKCE, ohne Client-Secret).

- Suche nach Titeln, Alben und Playlists
- Favoriten: Titel, Alben, Playlists, jeweils mit Trackliste, Cover und Qualitätsangabe (Lossless/Hi-Res)
- Ein Tipp auf einen Titel öffnet ihn in TIDAL. **Die Wiedergabe in dieser App** braucht eine
  Freigabe durch TIDAL (siehe unten).

**Einrichtung:**
1. Auf [developer.tidal.com](https://developer.tidal.com) mit dem TIDAL-Konto anmelden und im Dashboard eine App anlegen.
2. Als Redirect-URI `hiresplayer://tidal/callback` eintragen und die Scopes
   `user.read collection.read playlists.read search.read` aktivieren.
3. Die Client-ID im TIDAL-Tab der App eintragen und anmelden.

**Freigabe für die Wiedergabe:** TIDAL gibt Audio-Streams nur über die eigenen Player-SDKs heraus.
Für Drittanbieter-Apps sind öffentlich nur Vorschauen dokumentiert. Den Zugang für ganze Titel
fragt man über das Developer-Portal bzw. die
[TIDAL-Developer-Community](https://github.com/orgs/tidal-music/discussions) an.

## Ausgabemodi

1. **Standard + EQ** – Android-Mixer, EQ aktiv. Läuft mit jedem Gerät (auch Bluetooth).
2. **Bit-Perfect (USB, Android 14+)** – Integer-PCM-Pfad ohne DSP. 16-bit-Quellen (CD-Qualität)
   kommen unverändert am DAC an.
3. **Hi-Res Float** – 32-bit-Float-Pfad ohne DSP, Mixer mit nativer Abtastrate. Für 24-bit-Dateien gedacht.

Im Bit-Perfect-Modus wird die Lautstärke nicht digital geregelt, das übernimmt der DAC bzw. der Verstärker.

## Installieren

Jeder Push baut per GitHub Actions eine APK (Workflow *USB Audio Player (Android)* → Artifact
`hires-usb-player-apk`). Die Release-APK auf das Handy laden und installieren
(„Installation aus unbekannten Quellen“ erlauben).

Selbst bauen: Android Studio öffnen → Ordner `usb-audio-player` → *Run*, oder `./gradlew assembleRelease`.

## Ehrliche Grenzen (v0.1) und nächste Schritte

- **24-bit bit-perfect**: Android dekodiert FLAC als 16-bit oder Float. Float in einen 24-bit-Mixer
  ist verlustfrei, zählt für Android aber nicht als „bit-perfect“. Nächster Schritt: eigener
  `AudioSink`, der Float verlustfrei zurück in 24/32-bit-Integer wandelt.
- **Unter Android 14**: Ohne die Mixer-API läuft alles über den Android-Mixer. UAPP umgeht das
  mit einem eigenen USB-Treiber (libusb); das wäre die Ausbaustufe für ältere Geräte.
- **Noch nicht enthalten**: DSD/DoP, MQA, Streaming (Tidal/Qobuz), UPnP/DLNA, ReplayGain,
  Playlists und Ordneransicht.
