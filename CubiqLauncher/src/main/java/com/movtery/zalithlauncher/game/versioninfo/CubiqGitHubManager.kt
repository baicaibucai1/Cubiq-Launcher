/*
 * Cubiq Launcher
 * Checks GitHub releases for launcher updates.
 */

package com.movtery.zalithlauncher.game.versioninfo

import com.google.gson.JsonArray
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class CubiqReleaseItem(
    val tagName: String,
    val title: String,
    val htmlUrl: String,
    val downloadUrl: String?
)

object CubiqGitHubManager {
    private const val TAG = "CubiqGitHub"
    private const val URL_RELEASES = "https://api.github.com/repos/VivxKumar07/Cubiq-Launcher/releases?per_page=1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _newUpdate = MutableStateFlow<CubiqReleaseItem?>(null)
    val newUpdate = _newUpdate.asStateFlow()

    init {
        loadCache()
        scope.launch {
            try {
                val json = fetchStringFromUrls(listOf(URL_RELEASES))
                parseReleases(json)
                runCatching {
                    val cacheFile = File(PathManager.DIR_CACHE_HOME_PAGE, "cubiq_releases.json")
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeText(json)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.warning(TAG, "Failed to fetch GitHub releases", e)
            }
        }
    }

    private fun parseReleases(json: String) {
        try {
            val array = GSON.fromJson(json, JsonArray::class.java) ?: return
            val latest = array.firstOrNull { it.isJsonObject }?.asJsonObject ?: return

            val tagName = latest.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (tagName.isBlank()) return
            val title = latest.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?.ifBlank { tagName } ?: tagName
            val htmlUrl = latest.get("html_url")?.takeIf { it.isJsonPrimitive }?.asString
                ?: "https://github.com/VivxKumar07/Cubiq-Launcher/releases"

            val apkList = latest.getAsJsonArray("assets")?.mapNotNull { asset ->
                if (!asset.isJsonObject) return@mapNotNull null
                val entry = asset.asJsonObject
                val name = entry.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val url = entry.get("browser_download_url")?.takeIf { it.isJsonPrimitive }?.asString
                if (name.endsWith(".apk", ignoreCase = true) && !url.isNullOrBlank()) name to url else null
            }.orEmpty()

            val matched = when (getTargetApkKeyword()) {
                "arm64-v8a" -> apkList.firstOrNull { it.first.contains("arm64", ignoreCase = true) || it.first.contains("v8a", ignoreCase = true) }
                "armeabi-v7a" -> apkList.firstOrNull {
                    (it.first.contains("v7a", ignoreCase = true) || it.first.contains("armeabi", ignoreCase = true) ||
                        it.first.contains("-arm.", ignoreCase = true) || it.first.contains("-arm-", ignoreCase = true)) &&
                        !it.first.contains("arm64", ignoreCase = true)
                }
                "x86_64" -> apkList.firstOrNull { it.first.contains("x86_64", ignoreCase = true) }
                "x86" -> apkList.firstOrNull { it.first.contains("x86", ignoreCase = true) && !it.first.contains("x86_64", ignoreCase = true) }
                else -> null
            }
            val downloadUrl = matched?.second ?: apkList.firstOrNull()?.second
            checkUpdateAvailable(CubiqReleaseItem(tagName, title, htmlUrl, downloadUrl))
        } catch (e: Exception) {
            Logger.warning(TAG, "Error parsing GitHub releases: ${e.message}")
        }
    }

    private fun getTargetApkKeyword(): String {
        val arch = runCatching { BuildKeys.BUILD_ARCH.lowercase().trim() }.getOrDefault("")
        return when (arch) {
            "arm", "armeabi-v7a", "v7a" -> "armeabi-v7a"
            "arm64", "arm64-v8a", "aarch64" -> "arm64-v8a"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> {
                val is64Bit = runCatching { android.os.Process.is64Bit() }.getOrDefault(true)
                val isX86 = Architecture.isx86Device()
                when {
                    isX86 && is64Bit -> "x86_64"
                    isX86 && !is64Bit -> "x86"
                    !isX86 && is64Bit -> "arm64-v8a"
                    else -> "armeabi-v7a"
                }
            }
        }
    }

    private fun checkUpdateAvailable(latest: CubiqReleaseItem) {
        val currentVer = BuildConfig.VERSION_NAME.removePrefix("v").trim()
        val latestVer = latest.tagName.removePrefix("v").trim()

        if (isVersionGreater(latestVer, currentVer)) {
            val ignoredVersion = AllSettings.lastIgnoredVersion.getValue()
            val latestCode = parseVersionToCode(latestVer)
            if (ignoredVersion == null || ignoredVersion != latestCode) {
                _newUpdate.value = latest
            }
        }
    }

    fun dismissUpdate(ignoreForVersion: Boolean = false) {
        val current = _newUpdate.value
        if (ignoreForVersion && current != null) {
            val code = parseVersionToCode(current.tagName.removePrefix("v").trim())
            AllSettings.lastIgnoredVersion.save(code)
        }
        _newUpdate.value = null
    }

    private fun parseVersionToCode(version: String): Int {
        val parts = version.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val major = parts.getOrNull(0) ?: 1
        val minor = parts.getOrNull(1) ?: 0
        val patch = parts.getOrNull(2) ?: 0
        return major * 100000 + minor * 1000 + patch
    }

    private fun isVersionGreater(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun loadCache() {
        runCatching {
            val cacheFile = File(PathManager.DIR_CACHE_HOME_PAGE, "cubiq_releases.json")
            if (cacheFile.exists()) {
                parseReleases(cacheFile.readText())
            }
        }
    }
}
