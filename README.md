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

## Automatische Veröffentlichung

- Ein Push auf `master` oder `main` startet `.github/workflows/build.yml`.
- Du kannst den Workflow auch manuell über **Actions → Build → Run workflow** starten.
- Der Workflow erstellt die `.cs3`-Plugins und `plugins.json` und veröffentlicht sie im Branch `builds`.
- Neue Builds erhöhen die Plugin-Version anhand der GitHub-Actions-Laufnummer.
- In **Settings → Actions → General → Workflow permissions** muss für den Workflow **Read and write permissions** erlaubt sein, damit die Dateien im Branch `builds` veröffentlicht werden können.

## Wichtig

Ein erfolgreicher Build bestätigt, dass Plugin-Dateien erstellt wurden. Er garantiert nicht, dass jede Website erreichbar ist oder ihre HTML-Struktur, Suchfunktion und Stream-Links unverändert bleiben. Wenn ein Anbieter seine Website ändert, muss der betreffende `*Provider.kt`-Scraper möglicherweise angepasst und erneut getestet werden.

Die Provider sind Community-Integrationen und keine offiziellen CloudStream-Dienste. Verwende nur Inhalte, auf die du zugreifen darfst.
