# Battlemap – Konzept (Umsetzung nächste Woche)

Ziel: eine einfache Battlemap wie bei Foundry. Der Meister baut und steuert, die Spieler sehen nur, was ihre Helden sehen.

## Wo
- **Spieler:** neue Kachel „Karte“. Ein Klick öffnet die Karte im Vollbild, sie aktualisiert sich live wie der Soundstream.
- **Meister:** neuer Menüpunkt „Battlemap“ mit Kartenliste, Editor und Livebetrieb. Pro Session ist eine Karte aktiv.

## Karte
- Bild hochladen (PNG/JPG/WebP), so wie die MP3s.
- Raster drüberlegen: Feldgröße und Versatz per Schieberegler, 1 Feld = 1 Schritt.
- **Import aus Dungeondraft, DungeonFog usw. im Format Universal VTT (.dd2vtt):** Darin stecken Bild, Raster, Wände, Türen und Lichter fertig. Das spart das Nachzeichnen und ist der größte Zeitgewinn.

## Wände, Türen, Fenster (Meister-Editor)
- Linien klicken: **Wand** (blockiert Sicht und Bewegung), **Tür** (offen, zu oder verschlossen), **Fenster** (Sicht ja, Bewegung nein), **Geheimtür** (für Spieler wie eine Wand, bis der Meister sie aufdeckt).
- Türen öffnet und schließt der Meister per Klick. Spieler können eine Tür „antippen“: unverschlossen geht sie auf, verschlossen sieht der Meister die Anfrage.

## Spielfiguren
- Helden der Session automatisch, mit Heldenbild als Spielfigur. NSC und Gegner setzt der Meister, auch unsichtbar.
- Größen 1×1, 2×2, 3×3. Zustände als kleine Marker (bewusstlos, liegend, brennend …).
- **Bewegen:** Spieler ziehen nur die eigene Figur. Der Server prüft den Weg: nicht durch Wände, geschlossene Türen oder Fenster. Optional zählt er die GS pro Kampfrunde mit.
- **Modus:** frei (Erkunden) oder Kampf. Im Kampf darf nur ziehen, wer laut Initiativleiste dran ist.

## Sicht (Line of Sight) und Fog of War
- Die Sicht jeder Spielfigur ist ein Sichtpolygon: Strahlen gegen alle Wandsegmente. Das ist schnell genug für hunderte Wände.
- **Licht:** Lichtquellen mit Radius (Fackel, Laterne, Lagerfeuer). Tag, Dämmerung oder Nacht pro Karte. Dämmerungssicht und Nachtsicht aus den Vorteilen der Helden.xml verlängern die Sicht im Dunkeln.
- **Fog of War**, drei Zustände je Rasterfeld:
  1. **nie gesehen:** komplett schwarz;
  2. **schon gesehen:** abgedunkelt, ohne Figuren (Karte bleibt erinnert);
  3. **gerade sichtbar:** voll, mit Figuren.
- Gespeichert serverseitig als Bitmaske je Karte. **Gemeinsam für die Gruppe** (was einer gesehen hat, wissen alle) ist einfach und üblich; pro Held getrennt ginge auch.
- Der Meister sieht alles. Mit einem Pinsel kann er Bereiche von Hand aufdecken oder wieder verdecken.

## Gegen Schummeln
- Der Server schickt jedem Spieler nur, was er sehen darf: Figuren in Sicht und nur die entdeckten Kacheln des Kartenbilds. Die Karte wird in Kacheln geschnitten. Über die Entwicklertools lässt sich so nichts aufdecken.

## Werkzeuge (einfach gehalten)
- Messen in Schritt, „Ping“ (kurz aufleuchtender Punkt für alle), Initiativleiste (Werte aus den Helden), Würfel-Ergebnisse optional.

## Technik
- Browser: Canvas 2D, kein Framework. Live-Updates über dieselben Server-Sent Events wie beim Soundstream.
- Server: Karten und Wände als JSON in der Datenbank, Bilder und Kacheln im Datenordner. Sicht und Fog rechnet der Server, damit er nur Erlaubtes schickt.

## Umsetzung in Schritten
1. Karte hochladen, Raster, Figuren live verschieben (Meister und Spieler).
2. Wände und Türen zeichnen, Bewegung prüfen. Import .dd2vtt.
3. Line of Sight und Fog of War (Gruppe), Meister-Pinsel.
4. Licht, Tag und Nacht, Dämmerungssicht, Kampfmodus mit Initiative und GS.

## Offen für nächste Woche
- Fog gemeinsam für die Gruppe oder pro Held?
- Bewegung frei oder im Kampf streng nach Initiative?
- Woher kommen die Karten: eigene Bilder, Dungeondraft (.dd2vtt) oder beides?
