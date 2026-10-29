// use an integer for version numbers
version = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

cloudstream {
    language = "de"
    description = "Anime-Base.net - nicht verfügbar (Website geschlossen)"
    authors = listOf("Leokiii")

    // Provider remains listed for reference; the website currently announces closure.
    status = 0

    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=anime-base.net&sz=%size%"
