/*
 * Cubiq Launcher
 * Latest Minecraft release shown on the launcher home screen.
 */

package com.movtery.zalithlauncher.game.versioninfo

import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object MinecraftOfficialContentManager {
    private const val TAG = "MinecraftContent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _latestReleaseVersion = MutableStateFlow("1.21.4")
    val latestReleaseVersion = _latestReleaseVersion.asStateFlow()

    init {
        scope.launch {
            try {
                MinecraftVersions.latestRelease?.let { _latestReleaseVersion.value = it }
                _latestReleaseVersion.value = MinecraftVersions.getVersionManifest().latest.release
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.warning(TAG, "Failed to refresh latest Minecraft release", e)
            }
        }
    }
}
