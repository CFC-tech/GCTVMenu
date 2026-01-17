
package com.myappseasons.gctv

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private var latestVideoFile: File? = null

    private val TAG = "VideoApp"

    // VIDEO URLs HERE
    private val downloadUrls = listOf(
        "https://gcmenu.com/img/Branding_old.mp4",
        "https://gcmenu.com/img/mchdv.mp4",
    )

    private var currentDownloadIndex = 0

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "=== BROADCAST RECEIVED ===")
            try {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1
                Log.d(TAG, "Broadcast ID: $id, Our downloadId: $downloadId")
                if (id == downloadId) {
                    Log.d(TAG, "Matching download ID")
                    runOnUiThread { checkForDownloadedFile() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in receiver: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initial UI
        binding.btnPlay.isEnabled = false
        binding.btnPlay.text = "DOWNLOAD FIRST"
        binding.downloadStatusContainer.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.tvPercent.text = "0%"
        binding.tvStatus.text = "Idle"

        // Clicks
        binding.btnDownload.setOnClickListener { startBatchDownload() }
        binding.btnPlay.setOnClickListener { playVideo() }
        binding.btnRefresh.setOnClickListener { checkForDownloadedFile() }

        // Register receiver + initial check
        registerDownloadReceiver()
        checkForDownloadedFile()
    }

    private fun registerDownloadReceiver() {
        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(downloadReceiver, filter)
            }
            Log.d(TAG, "Broadcast receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver: ${e.message}")
        }
    }

    private fun checkForDownloadedFile() {
        Log.d(TAG, "=== CHECKING FOR DOWNLOADED FILES ===")
        showToast("Checking for files...")

        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Log.e(TAG, "Downloads directory not found")
            showToast("Downloads directory not found")
            return
        }

        val files = downloadsDir.listFiles()
        if (files.isNullOrEmpty()) {
            Log.d(TAG, "No files in downloads directory")
            showToast("No files found")
            return
        }

        var latestFile: File? = null
        var latestModified = 0L

        files.forEach { file ->
            if (file.isFile && file.name.endsWith(".mp4", ignoreCase = true)) {
                if (file.lastModified() > latestModified) {
                    latestModified = file.lastModified()
                    latestFile = file
                }
            }
        }

        if (latestFile != null) {
            latestVideoFile = latestFile
            runOnUiThread {
                binding.btnPlay.isEnabled = true
                binding.btnPlay.text = "PLAY VIDEO"
                showToast("Found: ${latestFile!!.name}")
            }
        } else {
            showToast("No video files found")
        }
    }
    // BATCH DOWNLOAD (QUEUE)
    private fun startBatchDownload() {
        if (downloadUrls.isEmpty()) {
            showToast("No URLs to download")
            return
        }
        currentDownloadIndex = 0

        // UI Reset
        binding.downloadStatusContainer.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvPercent.text = "0%"
        binding.tvStatus.text = "Preparing..."
        binding.btnPlay.isEnabled = false
        binding.btnPlay.text = "DOWNLOADING..."

        //  Start first item only
        startSingleDownload(downloadUrls[currentDownloadIndex])
    }

    // Single item download
    private fun startSingleDownload(url: String) {
        Log.d(TAG, "Starting download... $url")

        // UI for current item
        binding.downloadStatusContainer.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvPercent.text = "0%"
        binding.tvStatus.text = statusLabel("Preparing...")

        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Log.e(TAG, "Downloads directory is null")
            showToast("Cannot access downloads directory")
            return
        }

        // Create safe filename from URL
        val segment = Uri.parse(url).lastPathSegment ?: "video_${System.currentTimeMillis()}.mp4"
        val safeName = if (segment.endsWith(".mp4", true)) segment else "$segment.mp4"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading: $safeName")
            .setDescription("Video ${currentDownloadIndex + 1} of ${downloadUrls.size}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, safeName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType("video/mp4")

        val dm = getSystemService<DownloadManager>()
        if (dm == null) {
            Log.e(TAG, "DownloadManager is null")
            showToast("DownloadManager not available")
            return
        }

        downloadId = dm.enqueue(request)
        Log.d(TAG, "Download started with ID: $downloadId for $safeName")

        startProgressPolling(dm)
    }

    private fun statusLabel(base: String): String {
        return if (downloadUrls.size > 1) {
            "$base (${currentDownloadIndex + 1}/${downloadUrls.size})"
        } else base
    }

    // PROGRESS UI
    private fun startProgressPolling(dm: DownloadManager) {
        stopProgressPolling()  // clear any previous

        progressRunnable = object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                var cursor: Cursor? = null
                try {
                    cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val soFarCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                        val status = cursor.getInt(statusCol)
                        val total = cursor.getLong(totalCol)
                        val downloaded = cursor.getLong(soFarCol)
                        val reason = cursor.getInt(reasonCol)

                        when (status) {
                            DownloadManager.STATUS_PENDING -> {
                                updateProgressUI(downloaded, total, "Pending…"); scheduleNext()
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                updateProgressUI(downloaded, total, "Downloading…"); scheduleNext()
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                updateProgressUI(downloaded, total, "Paused ($reason)"); scheduleNext()
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                updateProgressUI(total, total, "Completed"); onDownloadCompleted()
                            }
                            DownloadManager.STATUS_FAILED -> {
                                updateProgressUI(0, 100, "Failed ($reason)"); onDownloadFailed(reason)
                            }
                        }
                    } else {
                        scheduleNext()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Progress query error: ${e.message}")
                    scheduleNext()
                } finally {
                    cursor?.close()
                }
            }
            private fun scheduleNext() = progressHandler.postDelayed(this, 500)
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
            binding.progressBar.progress = percent.coerceIn(0, 100)
            binding.tvPercent.text = "$percent%"
            binding.tvStatus.text = statusText
        }
    }

    // COMPLETE / FAIL HANDLERS
    private fun onDownloadCompleted() {
        Log.d(TAG, "Download completed")
        showToast("Download ${currentDownloadIndex + 1} completed")

        stopProgressPolling()
        checkForDownloadedFile() // refresh latest

        // Next item?
        if (currentDownloadIndex + 1 < downloadUrls.size) {
            currentDownloadIndex += 1
            binding.progressBar.progress = 0
            binding.tvPercent.text = "0%"
            binding.tvStatus.text = statusLabel("Preparing next…")
            startSingleDownload(downloadUrls[currentDownloadIndex])
        } else {
            binding.tvStatus.text = "All downloads completed (${downloadUrls.size})"
            binding.btnPlay.isEnabled = true
            binding.btnPlay.text = "PLAY VIDEO"
            showToast("All downloads completed")
        }
    }

    private fun onDownloadFailed(reason: Int) {
        Log.e(TAG, "Download failed with reason: $reason")
        showToast("Download failed: item ${currentDownloadIndex + 1}")
        stopProgressPolling()

        // Batch stop
        binding.btnPlay.isEnabled = false
        binding.btnPlay.text = "DOWNLOAD FIRST"
        binding.tvStatus.text = "Failed on ${currentDownloadIndex + 1}/${downloadUrls.size}"
    }

    private fun getAllDownloadedVideos(): ArrayList<String> {
        val list = arrayListOf<String>()
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return list

        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("mp4", true)) {
                list.add(file.absolutePath)
            }
        }
        // order control
        list.sort()
        return list
    }

    // PLAYBACK
    private fun playVideo() {
        val videoList = getAllDownloadedVideos()

        if (videoList.isEmpty()) {
            showToast("Please download videos first")
            return
        }

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putStringArrayListExtra("VIDEO_LIST", videoList)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        stopProgressPolling()
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Toast: $message")
        }
    }
}
