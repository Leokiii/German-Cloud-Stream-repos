// use an integer for version numbers
version = 1


cloudstream {
    language = "de"
    description = "Anime-Base.net - German anime community & streams"
    authors = listOf("Leokiii")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=anime-base.net&sz=%size%"
}
