package com.indo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PusatFilm21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PusatFilm21())
        registerExtractorAPI(Kotakajaib())
    }
}
