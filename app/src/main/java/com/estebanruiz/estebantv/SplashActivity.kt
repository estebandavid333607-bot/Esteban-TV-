package com.estebanruiz.estebantv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<View>(R.id.logo)
        val title = findViewById<View>(R.id.title)

        logo.alpha = 0f
        logo.scaleX = 0.7f
        logo.scaleY = 0.7f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).start()

        title.alpha = 0f
        title.animate().alpha(1f).setStartDelay(400).setDuration(600).start()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 2200)
    }
}
