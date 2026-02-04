package com.myappseasons.gctv

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.myappseasons.gctv.databinding.ActivityMainBinding
import timber.log.Timber
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var downloadId: Long = -1L

    private val downloadedFiles = mutableListOf<File>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    private val downloadUrls = listOf(
        "https://gcmenu.com/img/Branding_old.mp4",
        "https://gcmenu.com/img/mchdv.mp4",
        "https://gcmenu.com/img/Hot2.png",
        "https://gcmenu.com/img/Cold2.png"
    )

    private var currentDownloadIndex = 0

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == downloadId) {
                Timber.d("Download complete: $downloadId")
                runOnUiThread { checkForLatestFiles() }
            }
        }
    }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Timber.d("POST_NOTIFICATIONS granted: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Timber.plant(Timber.DebugTree())

        ensureNotificationPermissionOn33Plus()
        initUI()
        initSpinner()
        registerDownloadReceiver()
        checkForLatestFiles()
    }

    private fun ensureNotificationPermissionOn33Plus() {
        if (Build.VERSION.SDK_INT >= 33) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPermission.launch(permission)
            }
        }
    }

    private fun initUI() {
        // Better defaults for TV focus
        binding.btnDownload.isFocusable = true
        binding.btnPlay.isFocusable = true
        binding.btnImage.isFocusable = true
        binding.btnRefresh.isFocusable = true
        binding.spinner.isFocusable = true
        binding.btnDelete.isFocusable = true
        binding.btnDownload.isFocusable = true

        binding.btnDownload.isEnabled = false
        if (isInternetAvailable(this)) {
            binding.btnDownload.isEnabled = true
        }
        binding.btnImage.isEnabled = false
        binding.btnDelete.isEnabled = false
        binding.btnRefresh.isEnabled = false
        binding.btnPlay.text = "DOWNLOAD FIRST"
        binding.btnImage.text = "DOWNLOAD FIRST"
        binding.downloadStatusContainer.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.tvPercent.text = "0%"
        binding.tvStatus.text = "Idle"
        binding.btnDelete.text = "DELETE"

        binding.btnDownload.setOnClickListener {
            if (isInternetAvailable(this)) {
                startBatchDownload()
            } else
                Toast.makeText(this, "No Internet Connection", Toast.LENGTH_LONG).show()
        }
        binding.btnPlay.setOnClickListener { openVideoFiles() }
        binding.btnImage.setOnClickListener { openImageFiles() }
        binding.btnRefresh.setOnClickListener {
            refreshDownloadedFiles()
            binding.spinner.visibility = View.VISIBLE
            Toast.makeText(this, "Downloaded files loaded", Toast.LENGTH_SHORT).show()
        }
        binding.btnDelete.setOnClickListener {
            val position = binding.spinner.selectedItemPosition
            if (position == AdapterView.INVALID_POSITION || downloadedFiles.isEmpty()) {
                Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val file = downloadedFiles[position]

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Confirm Delete")
                .setMessage("Are you sure you want to delete this item?\n\n${file.name}")
                .setCancelable(true)
                .setPositiveButton("Delete") { dialog, _ ->
                    if (file.exists() && file.delete()) {
                        Toast.makeText(this, "Deleted Successfully: ${file.name}", Toast.LENGTH_SHORT).show()
                        refreshDownloadedFiles()
                    } else {
                        Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(downloadReceiver, filter)
            }
        } catch (e: Exception) {
            Timber.e(e, "registerReceiver failed")
        }
    }

    fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false

            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return info != null && info.isConnected
        }
    }

    private fun initSpinner() {
        spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf()
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinner.adapter = spinnerAdapter
    }

    private fun refreshDownloadedFiles() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return

        downloadedFiles.clear()
        spinnerAdapter.clear()

        dir.listFiles()?.filter { it.isFile }?.forEach {
            downloadedFiles.add(it)
            spinnerAdapter.add(it.name)
        }

        spinnerAdapter.notifyDataSetChanged()
    }

    // Check for both video and image files
    private fun checkForLatestFiles() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()

        val videoExists = files.any { it.isVideo() }
        val imageExists = files.any { it.isImage() }

        binding.btnPlay.isEnabled = videoExists
        binding.btnPlay.text = if (videoExists) "PLAY VIDEO" else "DOWNLOAD FIRST"
        binding.btnImage.isEnabled = imageExists
        binding.btnImage.text = if (imageExists) "VIEW IMAGE" else "DOWNLOAD FIRST"
        binding.btnDelete.isEnabled = files.isNotEmpty()
        binding.btnRefresh.isEnabled = true
    }

    private fun startBatchDownload() {
        if (downloadUrls.isEmpty()) return
        currentDownloadIndex = 0
        binding.downloadStatusContainer.visibility = View.VISIBLE
        updateProgressUI(0, 0, "Preparing…")
        disablePlayButtons("DOWNLOADING…")
        startSingleDownload(downloadUrls[currentDownloadIndex])
    }

    private fun startSingleDownload(url: String) {
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
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                            val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                            val total = if (totalIndex >= 0) cursor.getLong(totalIndex) else -1L
                            val downloaded = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L

                            when (status) {
                                DownloadManager.STATUS_RUNNING,
                                DownloadManager.STATUS_PENDING -> updateProgressUI(downloaded, total, "Downloading…")
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    updateProgressUI(total, total, "Completed")
                                    onDownloadCompleted()
                                    return // stop posting more
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                                    onDownloadFailed(reason)
                                    return
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Polling error")
                }
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
        val percent = if (total > 0) ((downloaded * 100f) / total).toInt() else 0
        binding.progressBar.progress = percent
        binding.tvPercent.text = "$percent%"
        binding.tvStatus.text = statusText
    }

    private fun onDownloadCompleted() {
        stopProgressPolling()
        if (currentDownloadIndex + 1 < downloadUrls.size) {
            currentDownloadIndex++
            startSingleDownload(downloadUrls[currentDownloadIndex])
        } else {
            binding.tvStatus.text = "All downloads completed"
            binding.downloadStatusContainer.visibility = View.GONE
            checkForLatestFiles()
            Toast.makeText(this, "All files downloaded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDownloadFailed(reason: Int) {
        stopProgressPolling()
        refreshDownloadedFiles()
        binding.tvStatus.text = "Failed on ${currentDownloadIndex + 1}/${downloadUrls.size}"
        disablePlayButtons("DOWNLOAD FIRST")
        Toast.makeText(this, "Download failed (reason: $reason)", Toast.LENGTH_LONG).show()
    }

    private fun disablePlayButtons(text: String) {
        binding.btnPlay.isEnabled = false
        binding.btnPlay.text = text
        binding.btnImage.isEnabled = false
        binding.btnImage.text = text
    }

    // Open Video / Image (intent for Activities)
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
    private fun File.isImage() = extension.equals("jpg", true) ||
            extension.equals("jpeg", true) || extension.equals("png", true)

    private fun String.getMimeType(): String = when {
        endsWith(".mp4", true) -> "video/mp4"
        endsWith(".jpg", true) || endsWith(".jpeg", true) -> "image/jpeg"
        endsWith(".png", true) -> "image/png"
        else -> "application/octet-stream"
    }
}