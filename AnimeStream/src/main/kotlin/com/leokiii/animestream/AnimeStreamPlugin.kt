package com.leokiii.animestream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeStreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeStreamProvider())
    }
}
