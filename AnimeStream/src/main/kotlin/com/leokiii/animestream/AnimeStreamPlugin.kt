package com.leokiii.animestream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeStreamProvider())
    }
}
