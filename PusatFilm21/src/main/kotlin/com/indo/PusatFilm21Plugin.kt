package com.indo

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PusatFilm21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PusatFilm21())

        openSettings = { ctx ->
            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan PusatFilm21")
                .setMessage("Login menggunakan akun Google melalui situs resmi PusatFilm21.")
                .setPositiveButton("Login Google") { _, _ ->
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://new1.pusatfilm21info.com/auth/login")
                    )
                    ctx.startActivity(intent)
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}
