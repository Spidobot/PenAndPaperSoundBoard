# Heldenhort – Notizen für Claude

Docker-Container (Unraid) mit zwei Weboberflächen: Meister (Port 8080) und Spieler (Port 8081).
Java 21, Javalin, SQLite, helden.jar per Reflection. Build: `mvn -q package -DskipTests` → `target/heldenhort.jar`.
Push auf `main` baut per GitHub Actions das Image auf ghcr.io.

## Arbeitsweise
- Antworten auf Deutsch, knapp, trocken.
- „Sammelmodus“ = nur nummerierte Punkte aufnehmen, nichts umsetzen. „umsetzen“ = umsetzen, testen, auf Branch und `main` pushen.
- Keine urheberrechtlich geschützten Inhalte (Regelwerke, Sounds) ins Repo, es ist öffentlich.
- JS/CSS-Links in den HTML-Seiten tragen `?v=@build.version@`, Maven setzt beim Build einen Zeitstempel ein (Cloudflare cacht /common/* stundenlang). Nicht von Hand pflegen.
- Fehler an den Browser als 4xx mit verständlichem deutschem Text (Cloudflare ersetzt 5xx).

## Regeltexte (ⓘ-Knöpfe, Regelzusammenfassung)
- Texte liegen nur in `/data/regeln/*.json` (per FTP), nie im Repo. `Regeln.java` lädt sie bei Änderung neu und ordnet Namen der helden.jar unscharf zu.
- Quellen: WdH, WdS (SF, Talente, Kampftechniken), WdZ (Rituale, Wesen/Dämonen), WdG, Liber Cantiones (Zauber), Zoo-Botanica (Tier-Fähigkeiten, Bestiarium), Tractatus Contra Daemones (Dämonen samt Spielwerten). Gleiche Texte aus mehreren Büchern werden zusammengelegt. Erzeugt aus den PDFs mit `_DSA/Heldenhort-Regeln/regeltexte_erzeugen.py` (bewusst außerhalb des Repos). Buchkosten werden entfernt, Kosten kommen aus der helden.jar.
- Meister-Text hat Vorrang vor dem Regeltext. Helden-PDF: Haken „Regelzusammenfassung anhängen“ (standardmäßig an, inkl. Zauber). Session: Knopf „Regelzusammenfassung“ (jede Regel einmal, mit Helden). Begleiter (gegenstand/Wesen im XML) als eigene Rubrik ab neuer Seite; Heroes.all() überspringt Wesen, damit Begleiterwerte nie beim Helden landen.

## Mails und Freigaben
- Freigaben: eine Mail je Held, erst wenn alle Punkte entschieden sind (30 s Pause für Korrekturen), mit Begründungen.
- Alle Mails: Text + HTML mit eingebettetem W20-Logo (resources/mail/logo.png) und wechselndem hochnäsigem Meister-Gruß.
- Wahre Namen (SF „Wahrer Name: Dämon“ mit Auswahl Qualität/…/Name) erscheinen als beschwörbare Wesen in der Begleiter-Rubrik.

## Stand
Sammelmodus aktiv. Offene Punkte: keine.
