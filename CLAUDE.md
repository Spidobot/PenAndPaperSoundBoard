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
- Texte liegen nur in `/data/regeln/regeln-*.json` (aufgeteilt nach Rubrik, per FTP), nie im Repo. Regeln liest eine Datei erst, wenn ihre Rubrik gebraucht wird (Regeln.TEIL_ART); ⓘ schickt ?art=, Seiten laden je Schritt/Bogen per /api/regelinfo/vorladen im Hintergrund vor. `Regeln.java` lädt sie bei Änderung neu und ordnet Namen der helden.jar unscharf zu.
- Quellen: WdH, WdS (SF, Talente, Kampftechniken), WdZ (Rituale, Wesen/Dämonen), WdG, Liber Cantiones (Zauber), Zoo-Botanica (Tier-Fähigkeiten, Bestiarium), Tractatus Contra Daemones (Dämonen samt Spielwerten). Gleiche Texte aus mehreren Büchern werden zusammengelegt. Erzeugt aus den PDFs mit `_DSA/Heldenhort-Regeln/regeltexte_erzeugen.py` (bewusst außerhalb des Repos). Buchkosten werden entfernt, Kosten kommen aus der helden.jar.
- Meister-Text hat Vorrang vor dem Regeltext. Helden-PDF: Haken „Regelzusammenfassung anhängen“ (standardmäßig an, inkl. Zauber). Session: Knopf „Regelzusammenfassung“ (jede Regel einmal, mit Helden). Begleiter (gegenstand/Wesen im XML) als eigene Rubrik ab neuer Seite; Heroes.all() überspringt Wesen, damit Begleiterwerte nie beim Helden landen.

## Voraussetzungen (Heldenerstellung)
- Jar zuerst: `Creation.bedingungsTester` (helden.framework.held.C.floatsuper = BedingungsTester); Gesamturteil MOEGLICH/UNKLAR, Einzelpunkte über den Bedingungsbaum. Nicht erfüllt → `fehlt`, gesperrt in der Auswahlliste (rot).
- Kennt die Jar keine Bedingung (`ungeprueft`): `Regeln.voraussetzungen` liest „Voraussetzungen:“ aus dem Regeltext und prüft Eigenschaften und kursive SF/Vorteile.
- Waffen: `App.waffenPruefen` – Kampftechnik nicht erlernt → roter Hinweis mit ersatzweisen Fertigkeiten aus WdS.

## Begleiter (Begleiter.java)
- Nur Vertraute (Held hat SF Vertrautenbindung, Begleiter wesensart Tier) haben eigene AP (WdZ S. 124): beim Aussenden automatisch ein Viertel der Helden-AP, Hexe kann AP übertragen (je 50 → Loyalität +1, max. 25). Steigern nach SKT-Spalte F, LE/AE/MR als Zukauf, max. 1,5 × Startwert (außer AE, Ritualkenntnis).
- Werte stehen im Helden-XML (<Wesen>), Startwerte/AP/LO/RK in der DB. Vertrautenzauber und Meister-Tricks NUR in der DB (begleiter_kann): die helden.jar kennt sie nicht und verwirft sonst beim Einlesen den ganzen Begleiter.
- Normale Tiere: keine AP, Meister setzt Werte im Heldenbogen (Abrichten, ZBA).
- Tier-Manöver/Tricks/Ausbildungen: Auswahl aus dem Setting „Tiere“ der helden.jar (settingstiere.xml), ins XML als <sonderfertigkeit name><auswahl name="Wert"/> (so speichert es die jar). Unbekannte Namen verwerfen beim Einlesen den ganzen Begleiter, darum nur Namen aus der Liste.
- PDFs (Helden-PDF, Session-Regelzusammenfassung): App.BEGLEITER.anreichern hängt Werte, Vertrauten-Daten und DB-Vertrautenzauber an.

## Mails und Freigaben
- Freigaben: eine Mail je Held, erst wenn alle Punkte entschieden sind (30 s Pause für Korrekturen), mit Begründungen.
- Alle Mails: Text + HTML mit eingebettetem W20-Logo (resources/mail/logo.png) und wechselndem hochnäsigem Meister-Gruß.
- Wahre Namen (SF „Wahrer Name: Dämon“ mit Auswahl Qualität/…/Name) erscheinen als beschwörbare Wesen in der Begleiter-Rubrik.

## Stand
Sammelmodus aktiv. Offene Punkte: keine.
