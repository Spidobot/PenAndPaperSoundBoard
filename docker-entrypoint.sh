#!/bin/sh
# Startet als root, legt die eigenen Ordner an, gibt sie PUID:PGID (Unraid: 99:100 = nobody:users)
# und startet Heldenhort dann als dieser Benutzer. Hochgeladene MP3s und Helden gehören damit nobody:users
# wie alles andere auf den Unraid-Shares. Der Sound-Ordner wird nicht umgeschrieben (kann z. B. Nextcloud gehören).
set -e
PUID="${PUID:-99}"
PGID="${PGID:-100}"
umask "${UMASK:-0002}"
if [ "$(id -u)" = "0" ]; then
  mkdir -p /data/home /data/jar /data/helden /data/db
  for d in /data /data/home /data/jar /data/helden /data/db; do chown "$PUID:$PGID" "$d" || true; done
  # Datenbank, Jar, Einstellungen: rekursiv (klein). Helden-XML: nur Dateien direkt im Ordner.
  chown -R "$PUID:$PGID" /data/home /data/jar /data/db 2>/dev/null || true
  find /data -maxdepth 1 -type f -exec chown "$PUID:$PGID" {} + 2>/dev/null || true
  find /data/helden -maxdepth 1 -type f -exec chown "$PUID:$PGID" {} + 2>/dev/null || true
  exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups \
    java -Djava.awt.headless=true -Duser.home=/data/home -jar /app/heldenhort.jar
fi
# schon als anderer Benutzer gestartet (z. B. --user): einfach so starten
exec java -Djava.awt.headless=true -Duser.home=/data/home -jar /app/heldenhort.jar
