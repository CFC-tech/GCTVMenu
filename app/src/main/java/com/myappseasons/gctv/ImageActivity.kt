
package com.myappseasons.gctv

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import java.io.File

class ImageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_image)
        hideSystemUI()

        val imageView = findViewById<ImageView>(R.id.imageView)

        // Get image list from intent
        val imagePaths = intent.getStringArrayListExtra("IMAGE_PATHS") ?: return
        if (imagePaths.isEmpty()) return

        val firstImagePath = imagePaths[0]

        // resize image
        Glide.with(this)
            .load(File(firstImagePath))
            .centerCrop()
            .into(imageView)
    }

    private fun hideSystemUI() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}