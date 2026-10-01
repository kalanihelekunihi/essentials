package com.sameerasw.essentials.weather.overcast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import com.google.gson.Gson
import com.sameerasw.essentials.weather.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OvercastWeather {
    const val PACKAGE = "com.sameerasw.overcast"
    const val PERMISSION = "com.sameerasw.overcast.permission.READ_WEATHER"
    const val INSTALL_URL = "https://github.com/sameerasw/Overcast/releases/latest"

    private const val AUTHORITY = "com.sameerasw.overcast.weather"
    private const val METHOD_REFRESH = "refresh"
    private const val COLUMN_JSON = "json"
    private const val SUPPORTED_SCHEMA = 1

    private val snapshotUri: Uri = "content://$AUTHORITY/snapshot".toUri()
    private val gson = Gson()

    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isAvailable(context: Context): Boolean = isInstalled(context) && hasPermission(context)

    fun openApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openInstallPage(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, INSTALL_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Last snapshot Overcast has. Null when it has nothing yet or the provider can't be reached.
    suspend fun fetch(context: Context): WeatherSnapshot? = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext null
        try {
            context.contentResolver.query(snapshotUri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val schema = cursor.getColumnIndex("schema").takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: SUPPORTED_SCHEMA
                if (schema > SUPPORTED_SCHEMA) return@use null
                gson.fromJson(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JSON)), WeatherSnapshot::class.java)
            }
        } catch (_: Exception) {
            null
        }
    }

    // Asks Overcast to fetch fresh data first. Returns false when it couldn't.
    suspend fun requestRefresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext false
        try {
            context.contentResolver.call(snapshotUri, METHOD_REFRESH, null, null)?.getBoolean("success", false) == true
        } catch (_: Exception) {
            false
        }
    }
}
