# 🇩🇪 German CloudStream Anime

Eine CloudStream-Erweiterungsquelle mit deutschsprachigen Anime-Anbietern.

## In CloudStream hinzufügen

Öffne **Einstellungen → Erweiterungen → Repository hinzufügen** und füge genau diese URL ein:

```text
https://raw.githubusercontent.com/Leokiii/German-Cloud-Stream-repos/master/repo.json
```

Verwende die `repo.json`-URL, nicht direkt `builds/plugins.json`. Das Manifest verweist automatisch auf die von GitHub Actions veröffentlichte Plugin-Liste.

## Anbieter und Sprachen

| Plugin | Webseite | Sprachen / Hinweise |
|---|---|---|
| AniWorld | https://aniworld.to | Deutsch synchronisiert (Ger-Dub) und deutsch untertitelt (Ger-Sub); je nach Titel gibt es weitere Optionen. |
| AnimeToast | https://www.animetoast.cc | Auf der Website werden Ger-Dub und Ger-Sub pro Titel angezeigt. |
| Anime-Stream | https://anime-stream.to | Deutschsprachiger Anbieter; die tatsächlichen Sprachen und Streams sind titelabhängig und müssen im Player geprüft werden. |
| Anime-Base | https://anime-base.net | Die Startseite meldet derzeit einen Umbau der Website. Die Funktion dieses Providers ist daher nicht bestätigt und kann eine Aktualisierung des Scrapers benötigen. |

Die Plugin-Metadaten verwenden `language = "de"`, damit CloudStream die Provider als deutschsprachig einordnen. Die Verfügbarkeit deutscher Synchronisation oder Untertitel hängt trotzdem von den einzelnen Titeln und der jeweiligen Website ab.

## Automatische Updates in CloudStream

**Du musst installierte Plugins bei einem normalen Update nicht löschen oder neu installieren.** Dieses Repository veröffentlicht höhere Plugin-Versionen, damit CloudStream die installierten Plugins als veraltet erkennt.

1. Füge das Repository **einmalig** mit der URL oben hinzu.
2. Aktiviere in CloudStream unter **Einstellungen → Updates** im Abschnitt **Erweiterungen** die Option **Automatische Plugin-Updates** (die Menübezeichnung kann je nach CloudStream-Version abweichen).
3. Beim ersten Wechsel auf die automatische Aktualisierung nutze einmal **Repository aktualisieren** bzw. **Alle Erweiterungen aktualisieren**. Danach aktualisiert CloudStream installierte Erweiterungen anhand der höheren Versionen, wenn es die Repository-Daten erneut abruft.

Die Auto-Update-Einstellung ist lokal in der CloudStream-App gespeichert und kann nicht per GitHub-Repository aus der Ferne aktiviert werden. Ohne diese Einstellung kann ein neues Plugin zwar veröffentlicht sein, die App übernimmt es aber nicht automatisch.

## Automatische Veröffentlichung und Versionsverwaltung

- Jeder Push auf `master` oder `main` startet `.github/workflows/build.yml`; ein manueller Start über **Actions → Build → Run workflow** ist ebenfalls möglich.
- Der Workflow baut die Plugins und erstellt `plugins.json`.
- Vor der Veröffentlichung prüft der Workflow jetzt, dass `plugins.json` gültig ist, keine doppelten Plugin-IDs enthält, jede referenzierte `.cs3`-Datei vorhanden ist und Dateigröße sowie SHA-256-Hash mit den Metadaten übereinstimmen.
- Erst wenn Build und Validierung erfolgreich sind, wird der Branch `builds` veröffentlicht. Ein fehlgeschlagener Build darf die zuletzt gültige Veröffentlichung nicht ersetzen.
- Die Plugin-Version wird aus der GitHub-Actions-Laufnummer gesetzt. Sie steigt bei späteren Builds an, damit CloudStream eine neuere Version erkennt.
- Build-Läufe werden nicht mehr mitten in der Veröffentlichung abgebrochen. Nach mehreren schnellen Commits soll der neueste ausstehende Lauf den aktuellen Code veröffentlichen.
- In **Settings → Actions → General → Workflow permissions** muss **Read and write permissions** erlaubt sein, damit GitHub Actions den Branch `builds` aktualisieren kann.

## Neue Website / neues Plugin hinzufügen

Wenn eine neue Website integriert werden soll, kann ein eigener Modulordner mit einer `build.gradle.kts`-Datei und dem CloudStream-Provider-/Plugin-Code angelegt werden. `settings.gradle.kts` erkennt solche Module automatisch. Nach dem Commit baut der Workflow sie, generiert die Metadaten und veröffentlicht sie mit den bestehenden Plugins. **`repo.json` und `plugins.json` müssen nicht von Hand gepflegt werden.** Sieh dir dazu [PROVIDER_TEMPLATE.md](PROVIDER_TEMPLATE.md) an.

Das automatisiert Build, Versionsnummern, Index und Veröffentlichung – es kann jedoch nicht selbstständig eine Website reparieren, wenn diese ihre HTML-Struktur oder Such-/Player-Funktion ändert. In so einem Fall muss der betroffene Provider-Code angepasst werden. Der CI-Build prüft Kompilierung und Veröffentlichung, nicht ob jede Live-Seite in CloudStream in jedem Moment korrekt funktioniert.

Die Provider sind Community-Integrationen und keine offiziellen CloudStream-Dienste. Verwende nur Inhalte, auf die du zugreifen darfst.
