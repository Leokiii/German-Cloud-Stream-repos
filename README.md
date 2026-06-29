# German CloudStream Repos

A [CloudStream](https://github.com/recloudstream) plugin repository with German anime providers.

## Add to CloudStream

In CloudStream: **Settings → Extensions → Add repository**, then paste:

```
https://raw.githubusercontent.com/Leokiii/German-Cloud-Stream-repos/builds/plugins.json
```

Then install the plugins you want from this repo.

## Included providers

| Plugin       | Site               | Notes                                   |
|--------------|--------------------|-----------------------------------------|
| AniWorld     | aniworld.to        | Also works via the aniworld.site mirror |
| AnimeToast   | animetoast.cc      | Ger-Dub & Ger-Sub                        |
| Anime-Stream | anime-stream.to    | Series & movies                          |
| Anime-Base   | anime-base.net     | Community streams                        |

## How the build works

- Push to `master`/`main` triggers `.github/workflows/build.yml`.
- The workflow builds every plugin into a `.cs3` file and generates `plugins.json`
  (via `./gradlew make makePluginsJson`), then force-pushes them to the **`builds`** branch.
- The workflow auto-creates the `builds` branch on the first run if it doesn't exist.
- CloudStream reads the raw `plugins.json` from the `builds` branch (the URL above).

## Building locally

- Windows: `.\gradlew.bat AniWorld:make`
- Linux & Mac: `./gradlew AniWorld:make`

Replace `AniWorld` with `AnimeToast`, `AnimeStream`, or `AnimeBase` to build a specific plugin.
New plugin folders (any directory with a `build.gradle.kts`) are picked up automatically by
`settings.gradle.kts`.

## Notes on maintenance

These providers scrape live websites. If a site changes its HTML layout, the relevant
CSS selectors in that provider's `*Provider.kt` (`getMainPage`, `search`, `load`, `loadLinks`)
may need updating. Each method is commented to make this easier.

## Attribution

The plugin system and gradle tooling are based on the
[CloudStream](https://github.com/recloudstream) project, which in turn is based on
[Aliucord](https://github.com/Aliucord).
