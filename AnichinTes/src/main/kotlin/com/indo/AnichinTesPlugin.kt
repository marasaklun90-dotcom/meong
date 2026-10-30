package com.indo

import com.lagradost.cloudstream3.plugins.*

@CloudstreamPlugin
class AnichinTesPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnichinTes())
    }
}
