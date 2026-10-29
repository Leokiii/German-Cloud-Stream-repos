package com.leokiii.aniworld

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniWorldPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AniWorldProvider())
    }
}
