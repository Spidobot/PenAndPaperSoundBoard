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
- Quellen: WdH, WdS, WdZ, WdG, Liber Cantiones (Zauber). Erzeugt aus den PDFs mit `_DSA/Heldenhort-Regeln/regeltexte_erzeugen.py` (bewusst außerhalb des Repos). Buchkosten werden entfernt, Kosten kommen aus der helden.jar.
- Meister-Text hat Vorrang vor dem Regeltext. Helden-PDF: Haken „Regelzusammenfassung anhängen“ (standardmäßig an, inkl. Zauber). Session: Knopf „Regelzusammenfassung“ (jede Regel einmal, mit Helden).

## Stand
Sammelmodus aktiv. Offene Punkte:
- 37: Streifenmuster im Verteil-Schritt der Heldenerstellung nicht durchgängig (Name, Wert und Eingabefeld haben verschiedene Hintergründe). Einheitlich pro Zeile oder ganz weg.
