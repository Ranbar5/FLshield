package com.example.applocker.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

class ApkSyncManager(private val context: Context, private val scope: CoroutineScope, private val http: OkHttpClient) {

    private val activeDownloads = mutableSetOf<String>()

    companion object {
        private const val TAG = "ApkSyncManager"
        const val ACTION_INSTALL_STATUS = "com.example.applocker.ACTION_INSTALL_STATUS"
    }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_INSTALL_STATUS) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val packageName = intent.getStringExtra("packageName") ?: ""
                val serverPackageName = intent.getStringExtra("serverPackageName") ?: packageName
                val version = intent.getStringExtra("version") ?: ""
                val filename = intent.getStringExtra("filename") ?: ""

                Log.d(TAG, "Install status for $packageName: status=$status, msg=$message")

                // Cleanup download files
                if (filename.isNotEmpty()) {
                    val fileByFilename = File(context.cacheDir, filename)
                    if (fileByFilename.exists()) fileByFilename.delete()
                }
                val fileByPkg = File(context.cacheDir, "$serverPackageName.apk")
                if (fileByPkg.exists()) fileByPkg.delete()
                val fileByActualPkg = File(context.cacheDir, "$packageName.apk")
                if (fileByActualPkg.exists()) fileByActualPkg.delete()

                if (status == PackageInstaller.STATUS_SUCCESS) {
                    Log.i(TAG, "Successfully installed $packageName (serverPackageName=$serverPackageName)")
                    val syncPrefs = context.getSharedPreferences("apk_sync_prefs", Context.MODE_PRIVATE)
                    syncPrefs.edit()
                        .putString(packageName, version)
                        .putString(serverPackageName, version)
                        .apply()

                    context.sendBroadcast(Intent("com.example.applocker.CONFIG_UPDATED"))
                } else {
                    Log.e(TAG, "Failed to install $packageName: $message (status=$status)")
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter(ACTION_INSTALL_STATUS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(installStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(installStatusReceiver, filter)
            }
            Log.d(TAG, "InstallStatusReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register install status receiver", e)
        }
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(installStatusReceiver)
        } catch (_: Exception) {}
    }

    fun syncApks(apksArray: JSONArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    Log.i(TAG, "Not Device Owner; skipping background APK sync to prevent endless failing downloads.")
                    return@launch
                }

                val syncPrefs = context.getSharedPreferences("apk_sync_prefs", Context.MODE_PRIVATE)
                for (i in 0 until apksArray.length()) {
                    val apkObj = apksArray.getJSONObject(i)
                    val packageName = apkObj.getString("packageName")
                    val url = apkObj.getString("url")
                    val version = apkObj.optString("version", "")
                    val filename = apkObj.optString("filename", "")

                    val storedVersion = syncPrefs.getString(packageName, null) ?: syncPrefs.getString(filename, null)
                    val isInstalled = isAppInstalled(packageName)

                    if (!isInstalled || storedVersion != version) {
                        var shouldSkip = false
                        synchronized(activeDownloads) {
                            if (activeDownloads.contains(packageName) || (filename.isNotEmpty() && activeDownloads.contains(filename))) {
                                shouldSkip = true
                            } else {
                                activeDownloads.add(packageName)
                                if (filename.isNotEmpty()) {
                                    activeDownloads.add(filename)
                                }
                            }
                        }

                        if (shouldSkip) {
                            Log.d(TAG, "Sync for $packageName / $filename already in progress. Skipping duplicate download.")
                            continue
                        }

                        Log.i(TAG, "App $packageName is not installed or version changed (stored=$storedVersion, server=$version). Syncing...")
                        scope.launch(Dispatchers.IO) {
                            try {
                                downloadAndInstallApk(packageName, url, version, filename)
                            } finally {
                                synchronized(activeDownloads) {
                                    activeDownloads.remove(packageName)
                                    if (filename.isNotEmpty()) {
                                        activeDownloads.remove(filename)
                                    }
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "App $packageName is already installed and up to date.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncApks", e)
            }
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun downloadAndInstallApk(packageName: String, url: String, version: String, filename: String) {
        val nameToUse = filename.ifEmpty { "$packageName.apk" }
        Log.d(TAG, "Downloading APK for $packageName from $url as $nameToUse")
        val file = File(context.cacheDir, nameToUse)
        
        try {
            val request = Request.Builder().url(url).build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download $packageName: code=${response.code}")
                return
            }

            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Downloaded APK for $packageName to ${file.absolutePath}")

            val success = installPackage(file, packageName, version, nameToUse)
            if (!success) {
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/installing $packageName", e)
            if (file.exists()) file.delete()
        }
    }

    private fun isZipFile(file: File): Boolean {
        return try {
            java.util.zip.ZipFile(file).use {}
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun installPackage(apkFile: File, serverPackageName: String, version: String, filename: String): Boolean {
        val packageManager = context.packageManager
        val packageInfo = if (!isZipFile(apkFile)) {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } else {
            null
        }
        val actualPackageName = packageInfo?.packageName ?: serverPackageName

        Log.d(TAG, "Preparing install of $actualPackageName (serverPackageName=$serverPackageName)")

        val packageInstaller = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(actualPackageName)
        }

        var session: PackageInstaller.Session? = null
        try {
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            if (isZipFile(apkFile) || filename.endsWith(".xapk")) {
                Log.i(TAG, "Installing $actualPackageName as split APK bundle (XAPK)...")
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith(".apk") && !entry.isDirectory) {
                            Log.d(TAG, "Writing split APK to session: ${entry.name} (${entry.size} bytes)")
                            zip.getInputStream(entry).use { inputStream ->
                                session.openWrite(entry.name, 0, entry.size).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                    session.fsync(outputStream)
                                }
                            }
                        }
                    }
                }
            } else {
                Log.i(TAG, "Installing $actualPackageName as standard single APK...")
                apkFile.inputStream().use { inputStream ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        session.fsync(outputStream)
                    }
                }
            }

            val intent = Intent(ACTION_INSTALL_STATUS).apply {
                setPackage(context.packageName)
                putExtra("packageName", actualPackageName)
                putExtra("serverPackageName", serverPackageName)
                putExtra("version", version)
                putExtra("filename", filename)
            }
            
            val requestCode = actualPackageName.hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pendingIntent.intentSender)
            Log.d(TAG, "Committed installation session for $actualPackageName")

            try {
                val syncPrefs = context.getSharedPreferences("apk_sync_prefs", Context.MODE_PRIVATE)
                syncPrefs.edit()
                    .putString(actualPackageName, version)
                    .putString(serverPackageName, version)
                    .apply()
                Log.d(TAG, "Saved sync version in preferences: $version")
            } catch (prefEx: Exception) {
                Log.e(TAG, "Failed to write preferences", prefEx)
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception installing $actualPackageName", e)
            return false
        } finally {
            session?.close()
        }
    }
}
