# Musik-Assistent

Eigener Sprachassistent für Android (getestet für OnePlus 9 Pro / OxygenOS 14) zum Steuern von Tidal und Spotify per Stimme, z. B. im Auto.

## So funktioniert's
1. **Aktivierungswort** (Standard: „hallo musik“) wird komplett **offline** erkannt (Vosk). Weil nur auf ein paar Wörter gehört wird, braucht das wenig Rechenleistung.
2. Nach dem **Piepton** sagst du deinen Befehl. Die Musik wird dabei kurz leiser.
3. Der Befehl wird mit der Google-Spracherkennung verstanden (gut bei englischen Titeln). Ohne Netz fällt die App automatisch auf offline zurück.

## Befehle
| Sagen | Wirkung |
|---|---|
| weiter / nächster | nächster Song |
| zurück | vorheriger Song |
| pause / stopp | anhalten |
| play / fortsetzen | weiterspielen |
| lauter / leiser | Lautstärke |
| spiel *Titel/Künstler* | sucht und spielt in der Standard-App |
| spiel *…* auf Spotify / auf Tidal | in einer bestimmten App |
| Assistent aus | Lauschen beenden |

## APK über GitHub bauen
1. Neues Repository auf GitHub anlegen und den Inhalt dieses Ordners hochladen (inkl. `.github`-Ordner und `app/signing.keystore`).
2. Der Workflow **„APK bauen“** startet automatisch (Reiter *Actions*). Dauert ca. 5 Minuten.
3. Im fertigen Lauf unten unter *Artifacts* **MusikAssistent-APK** herunterladen, entpacken, APK installieren.

Neue Versionen lassen sich direkt drüber installieren, da immer derselbe Schlüssel verwendet wird.

## Einrichtung am Handy
In der App die drei Schritte unter „Einrichtung“ antippen:
1. Mikrofon & Benachrichtigungen erlauben
2. „Über anderen Apps einblenden“ erlauben – sonst darf die App Tidal/Spotify nicht aus dem Hintergrund öffnen
3. Akku-Optimierung ausschalten – OxygenOS beendet die App sonst nach kurzer Zeit

Zusätzlich empfohlen: *Einstellungen → Akku → Musik-Assistent → Hintergrundaktivität erlauben* und die App in der Übersicht der zuletzt genutzten Apps mit dem Schloss fixieren.

**Schnellkachel:** Statusleiste runterziehen → Stift → „Musik-Assistent“ hinzufügen. Damit schaltest du ihn beim Einsteigen mit einem Tipp an.

## Hinweise
- **Songsuche bei Tidal:** Spotify spielt gesuchte Songs zuverlässig direkt ab. Ob Tidal direkt abspielt oder nur die Suche öffnet, hängt von der Tidal-Version ab. Falls nur die Suche aufgeht, ist das eine Grenze der Tidal-App.
- **Aktivierungswort:** Nur deutsche Wörter verwenden, die das Offline-Modell kennt. Zwei Wörter lösen seltener aus Versehen aus.
- **Akku:** Dauerhaftes Lauschen kostet immer etwas Strom. Im Auto am Ladekabel ist das egal, sonst einfach über die Kachel ausschalten.
