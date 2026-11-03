package com.indo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Kiblat21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kiblat21())
        registerExtractorAPI(TwoVCdn())
        registerExtractorAPI(TwoVVsrc())
        registerExtractorAPI(TwoVVidem())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(Vidlink())
        registerExtractorAPI(Vidzee(context.applicationContext))
    }
}
