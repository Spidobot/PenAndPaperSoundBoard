# Heldenhort

DSA-Spieltisch für Unraid: Soundboard mit Stream an die Spieler, Heldenerstellung über die helden.jar, Meisterfreigaben per Mail, Session-Suche über alle Heldenwerte.

Zwei Oberflächen in einem Container:

| Port | Oberfläche | Zugang |
|------|-----------|--------|
| 8080 | Meister-UI | Admin-Passwort aus `ADMIN_PASSWORD` |
| 8081 | Spieler-UI | Spieler-Passwort, festgelegt in der Meister-UI |

Beide können über den Nginx Proxy Manager ins Netz. Das Login-Cookie wird automatisch `Secure`, wenn NPM per HTTPS weiterleitet.

## Einrichtung auf Unraid

1. `unraid/heldenhort.xml` nach `/boot/config/plugins/dockerMan/templates-user/` kopieren und unter *Docker → Add Container* auswählen.
2. Pfade setzen:
   - **Daten** `/data` → `/mnt/user/appdata/heldenhort`
   - **Sounds** `/data/audio` → der `_DSA`-Ordner mit `Music/` und `Sounds/`
   - **Helden-XML** `/data/helden` → ein Ordner, den du per FTP erreichst
3. `ADMIN_PASSWORD` setzen.
4. `helden.jar` per FTP nach `/mnt/user/appdata/heldenhort/jar/helden.jar` legen. Sie liegt aus Lizenzgründen nicht im Image. Danach aktualisiert sie sich selbst, einmal täglich zur eingestellten Uhrzeit.
5. In der Meister-UI unter *Konfiguration*: Spieler-Passwort, Mail-Empfänger und SMTP eintragen, Testmail schicken.

## Ordner

```
/data
├── audio/                 ← _DSA
│   ├── Music/{Village,Travel,Dungeon,Castle,Fight}/*.mp3
│   ├── Sounds/{Village,Travel,Dungeon,Castle,Fight}/*.mp3
│   └── Effects/           Schatztruhe.mp3 Kuh.mp3 Avengers.mp3 Bertha.mp3
│                          QuestHinzugefuegt.mp3 QuestAbgeschlossen.mp3 Pizza.mp3
├── helden/*.xml           Helden (Spieler-Upload oder FTP)
├── db/heldenhort.sqlite   Einstellungen, Sessions, Freigaben, Status
├── jar/helden.jar         Helden-Software
└── home/                  Einstellungen der helden.jar
```

Alles liegt auf Volumes. Ein Update des Containers ersetzt nur das Image, die Daten bleiben.

Sounds kommen per Upload in der Meister-UI (*Soundboard → Dateiordner*) oder per FTP dazu. Liegt `_DSA` im Datenordner von Nextcloud, sieht Nextcloud neue Dateien erst nach `occ files:scan`.

## Image bauen

Bei jedem Push auf `main` baut GitHub Actions `ghcr.io/spidobot/heldenhort:latest`. Lokal:

```bash
docker build -t heldenhort .
```

## Stand

- Fertig: Soundboard (Szenen, Musik und Hintergrund, Effekte, Bertha, Upload), Stream an die Spieler, Meister- und Spieler-Login, Helden-Import und Session-Suche, Meisterfreigaben mit Mail, Konfiguration, tägliches Update der helden.jar.
- In Arbeit: Heldenerstellung. Regelbasis, Geschlecht, Rasse, Kultur und der Hintergrund-Editor sind an die helden.jar angebunden. Profession, Eigenschaften, Vor- und Nachteile, Talente, Regelprüfung und XML-Export folgen.

Icons: [Icons8](https://icons8.com).
