# Neue CloudStream-Website integrieren

Diese Anleitung ist für dieses Repository gedacht. Du musst nach dem Anlegen eines Providers **keine** Einträge in `settings.gradle.kts`, `repo.json` oder `plugins.json` manuell ergänzen.

## Was automatisch passiert

Wenn ein neues Provider-Modul mit einer `build.gradle.kts`-Datei im Hauptverzeichnis angelegt und auf `master` veröffentlicht wird:

1. `settings.gradle.kts` erkennt den Modulordner automatisch.
2. GitHub Actions kompiliert die Provider.
3. Gradle erzeugt die Metadaten für `plugins.json`.
4. Der Workflow prüft jeden Eintrag gegen die erstellte `.cs3`-Datei, Größe, Hash und Versionsnummer.
5. Nur bei erfolgreichem Build und bestandenen Prüfungen wird der Branch `builds` aktualisiert.
6. Durch die neue GitHub-Actions-Laufnummer erhält jede Erweiterung eine höhere Version. CloudStream kann sie dadurch als Update erkennen.

## Dateien für einen neuen Provider

Beispiel für einen Provider namens `ExampleAnime`:

```text
ExampleAnime/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    └── kotlin/com/exampleanime/
        ├── ExampleAnimePlugin.kt
        └── ExampleAnimeProvider.kt
```

### `build.gradle.kts`

Die Modul-Metadaten sollten mindestens Folgendes enthalten:

```kotlin
version = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

cloudstream {
    language = "de"
    description = "Kurzbeschreibung der Website"
    authors = listOf("Leokiii")
    status = 3 // 0 = offline, 1 = online, 2 = langsam, 3 = Beta
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://example.com/favicon.ico"
}
```

Der tatsächliche Status und die Beschreibung müssen zum Zustand des Anbieters passen. Die Website muss nicht unbedingt Ger-Dub und Ger-Sub für alle Titel anbieten, nur weil `language = "de"` gesetzt ist.

### `ExampleAnimePlugin.kt`

Das Plugin registriert den Provider bei CloudStream:

```kotlin
package com.exampleanime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ExampleAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ExampleAnimeProvider())
    }
}
```

### `ExampleAnimeProvider.kt`

Der Provider muss für die konkrete Website implementiert werden. Die benötigten Teile hängen von deren Aufbau ab, enthalten aber normalerweise:

- `mainUrl`, `name`, `lang`, `hasMainPage` und `supportedTypes`
- `mainPage` und `getMainPage` für aktuelle/erkundbare Inhalte
- `search` mit korrekt kodierten Suchbegriffen
- `load` für Titel, Poster, Beschreibung, Jahr und Folgen
- `loadLinks` für unterstützte Wiedergabequellen

Die CSS-Selektoren und URL-Muster müssen anhand der echten Website ermittelt werden. Nicht einfach die Selektoren eines anderen Anbieters übernehmen.

## Was du mir für eine neue Website sagen musst

Schicke mir einfach die Website-URL und den gewünschten Anzeigenamen. Falls bekannt, nenne auch, ob es Ger-Dub, Ger-Sub oder andere Sprachen gibt und ob du sie als Anime, Filme oder beides anzeigen willst. Ich kann dann das passende Modul erstellen und committen. Nach einem erfolgreichen Build wird es automatisch in derselben CloudStream-Repository-Liste veröffentlicht.

## Was „automatisch“ bedeutet – und was nicht

Der Build, die Versionsnummer, der Index und die Veröffentlichung laufen automatisch. Auf dem Handy muss die CloudStream-Einstellung für automatische Erweiterungsupdates einmal aktiviert sein. Das Repository kann diese lokale App-Einstellung nicht aus der Ferne ändern.

Eine Website-Änderung kann ein Plugin kaputtmachen, wird aber nicht zuverlässig automatisch erkannt oder automatisch repariert. Wenn der CI-Build fehlschlägt, wird die neue Version nicht veröffentlicht; wenn die Website inhaltlich anders reagiert, braucht es einen gezielten Code-Fix und danach einen neuen Build.
