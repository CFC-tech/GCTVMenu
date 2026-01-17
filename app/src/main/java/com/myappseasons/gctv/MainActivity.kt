package com.myappseasons.gctv

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.myappseasons.gctv.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var downloadId: Long = -1L

    private val downloadUrls = listOf(
        "https://gcmenu.com/img/Branding_old.mp4",
        "https://gcmenu.com/img/mchdv.mp4",
        "https://gcmenu.com/img/Hot2.png",
        "https://gcmenu.com/img/Cold2.png"
    )

    private var currentDownloadIndex = 0

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1
            if (id == downloadId) runOnUiThread { checkForLatestFiles() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initUI()
        registerDownloadReceiver()
        checkForLatestFiles()
    }

    private fun initUI() {
        binding.btnPlay.isEnabled = false
        binding.btnImage.isEnabled = false
        binding.btnPlay.text = "DOWNLOAD FIRST"
        binding.btnImage.text = "DOWNLOAD FIRST"
        binding.downloadStatusContainer.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.tvPercent.text = "0%"
        binding.tvStatus.text = "Idle"

        binding.btnDownload.setOnClickListener { startBatchDownload() }

        // **NEW:** separate buttons for videos and images
        binding.btnPlay.setOnClickListener { openVideoFiles() }
        binding.btnImage.setOnClickListener { openImageFiles() }

        binding.btnRefresh.setOnClickListener { checkForLatestFiles() }
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(downloadReceiver, filter)
        } catch (_: Exception) {}
    }

    // Check for both video and image files
    private fun checkForLatestFiles() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: return

        val videoExists = files.any { it.isVideo() }
        val imageExists = files.any { it.isImage() }

        runOnUiThread {
            binding.btnPlay.isEnabled = videoExists
            binding.btnPlay.text = if (videoExists) "PLAY VIDEO" else "DOWNLOAD FIRST"
            binding.btnImage.isEnabled = imageExists
            binding.btnImage.text = if (imageExists) "VIEW IMAGE" else "DOWNLOAD FIRST"
        }
    }

    private fun startBatchDownload() {
        if (downloadUrls.isEmpty()) return
        currentDownloadIndex = 0
        binding.downloadStatusContainer.visibility = View.VISIBLE
        updateProgressUI(0, 0, "Preparing...")
        disablePlayButtons("DOWNLOADING...")
        startSingleDownload(downloadUrls[currentDownloadIndex])
    }

    private fun startSingleDownload(url: String) {
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val fileName = Uri.parse(url).lastPathSegment ?: "file_${System.currentTimeMillis()}"
        val mimeType = fileName.getMimeType()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading: $fileName")
            .setDescription("File ${currentDownloadIndex + 1} of ${downloadUrls.size}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType(mimeType)

        val dm = getSystemService<DownloadManager>() ?: return
        downloadId = dm.enqueue(request)
        startProgressPolling(dm)
    }

    private fun startProgressPolling(dm: DownloadManager) {
        stopProgressPolling()
        progressRunnable = object : Runnable {
            override fun run() {
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    dm.query(query)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                            val total = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val downloaded = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            when (status) {
                                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING ->
                                    updateProgressUI(downloaded, total, "Downloading…")
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    updateProgressUI(total, total, "Completed")
                                    onDownloadCompleted()
                                }
                                DownloadManager.STATUS_FAILED ->
                                    onDownloadFailed(cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON)))
                            }
                        }
                    }
                } catch (_: Exception) {}
                progressHandler.postDelayed(this, 500)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun updateProgressUI(downloaded: Long, total: Long, statusText: String) {
        runOnUiThread {
            val percent = if (total > 0) ((downloaded * 100f) / total).toInt() else 0
            binding.progressBar.progress = percent
            binding.tvPercent.text = "$percent%"
            binding.tvStatus.text = statusText
        }
    }

    private fun onDownloadCompleted() {
        stopProgressPolling()
        if (currentDownloadIndex + 1 < downloadUrls.size) {
            currentDownloadIndex++
            startSingleDownload(downloadUrls[currentDownloadIndex])
        } else {
            runOnUiThread {
                binding.tvStatus.text = "All downloads completed"
                checkForLatestFiles()
            }
        }
    }

    private fun onDownloadFailed(reason: Int) {
        stopProgressPolling()
        runOnUiThread {
            binding.tvStatus.text = "Failed on ${currentDownloadIndex + 1}/${downloadUrls.size}"
            disablePlayButtons("DOWNLOAD FIRST")
            Toast.makeText(this, "Download failed (reason: $reason)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disablePlayButtons(text: String) {
        binding.btnPlay.isEnabled = false
        binding.btnPlay.text = text
        binding.btnImage.isEnabled = false
        binding.btnImage.text = text
    }

    // Open Video / Image ( intent for Activities )
    private fun openVideoFiles() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val videoFiles = dir.listFiles()?.filter { it.isVideo() }?.map { it.absolutePath } ?: return
        if (videoFiles.isEmpty()) return
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putStringArrayListExtra("VIDEO_LIST", ArrayList(videoFiles))
        startActivity(intent)
    }

    private fun openImageFiles() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val imageFiles = dir.listFiles()?.filter { it.isImage() }?.map { it.absolutePath } ?: return
        if (imageFiles.isEmpty()) return
        val intent = Intent(this, ImageActivity::class.java)
        intent.putStringArrayListExtra("IMAGE_PATHS", ArrayList(imageFiles))
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        stopProgressPolling()
    }

    // --- Helpers ---
    private fun File.isVideo() = extension.equals("mp4", true)
    private fun File.isImage() = extension.equals("jpg", true) || extension.equals("jpeg", true) || extension.equals("png", true)
    private fun String.getMimeType(): String = when {
        endsWith(".mp4", true) -> "video/mp4"
        endsWith(".jpg", true) || endsWith(".jpeg", true) -> "image/jpeg"
        endsWith(".png", true) -> "image/png"
        else -> "application/octet-stream"
    }
}
