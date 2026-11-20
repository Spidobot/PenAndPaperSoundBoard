# Heldenhort – Notizen für Claude

Docker-Container (Unraid) mit zwei Weboberflächen: Meister (Port 8080) und Spieler (Port 8081).
Java 21, Javalin, SQLite, helden.jar per Reflection. Build: `mvn -q package -DskipTests` → `target/heldenhort.jar`.
Push auf `main` baut per GitHub Actions das Image auf ghcr.io.

## Arbeitsweise
- Antworten auf Deutsch, knapp, trocken.
- „Sammelmodus“ = nur nummerierte Punkte aufnehmen, nichts umsetzen. „umsetzen“ = umsetzen, testen, auf Branch und `main` pushen.
- Keine urheberrechtlich geschützten Inhalte (Regelwerke, Sounds) ins Repo, es ist öffentlich.
- Fehler an den Browser als 4xx mit verständlichem deutschem Text (Cloudflare ersetzt 5xx).

## Stand
Sammelmodus aktiv. Offene Punkte:
- 37: Streifenmuster im Verteil-Schritt der Heldenerstellung nicht durchgängig (Name, Wert und Eingabefeld haben verschiedene Hintergründe). Einheitlich pro Zeile oder ganz weg.
- 38: Regeltexte für die ⓘ-Knöpfe (Vorteile, Nachteile, Sonderfertigkeiten) aus den Regelwerk-PDFs im Ordner `_DSA` (eine Ebene über diesem Repo) ziehen. Nur lokal bzw. im Container unter /data ablegen, nie ins Repo. Vom Meister gepflegter Text hat Vorrang.
