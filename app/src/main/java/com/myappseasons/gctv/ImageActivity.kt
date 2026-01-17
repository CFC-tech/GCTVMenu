package com.myappseasons.gctv

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.io.File

class ImageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image) // ImageView layout

        val imageView = findViewById<ImageView>(R.id.imageView)

        // Get image list from intent
        val imagepaths = intent.getStringArrayListExtra("IMAGE_PATHS") ?: return
        if (imagepaths.isEmpty()) return

        // Load the first image from the list
        val firstImagePath = imagepaths[0]
        Glide.with(this)
            .load(File(firstImagePath))
            .into(imageView)
    }
}
