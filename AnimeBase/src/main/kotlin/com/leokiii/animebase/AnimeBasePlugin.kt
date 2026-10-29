package com.leokiii.animebase

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeBasePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeBaseProvider())
    }
}
