package com.leokiii.animetoast

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeToastPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeToastProvider())
    }
}
