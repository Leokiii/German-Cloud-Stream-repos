// use an integer for version numbers
version = 1


cloudstream {
    language = "de"
    // All of these properties are optional, you can safely remove them

    description = "AniWorld.to - German anime (subbed & dubbed)"
    authors = listOf("Leokiii")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of avaliable types here:
    // https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=aniworld.to&sz=%size%"
}
