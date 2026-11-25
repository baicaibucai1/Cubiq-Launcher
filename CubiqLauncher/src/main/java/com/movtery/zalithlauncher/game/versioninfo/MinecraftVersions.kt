/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.versioninfo

import android.util.AtomicFile
import com.google.gson.reflect.TypeToken
import com.movtery.zalithlauncher.game.addons.mirror.MirrorPriority
import com.movtery.zalithlauncher.game.addons.mirror.gameDownloadPriority
import com.movtery.zalithlauncher.game.addons.mirror.mapBMCLMirrorUrls
import com.movtery.zalithlauncher.game.versioninfo.models.VersionManifest
import com.movtery.zalithlauncher.game.versioninfo.models.mapVersion
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.path.URL_MINECRAFT_VERSION_REPOS
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.file.readString
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrls
import com.movtery.zalithlauncher.utils.network.withRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "MinecraftVersions"

object MinecraftVersions {
    private val manifestLock = Mutex()
    private val manifests = ConcurrentHashMap<MirrorPriority, VersionManifest>()
    @Volatile private var publishedPriority: MirrorPriority? = null

    val latestRelease: String?
        get() = gameDownloadPriority().let { priority ->
            manifests[priority]?.latest?.release ?: runCatching {
                parseManifest(AtomicFile(manifestFile(priority)).readFully().decodeToString()).latest.release
            }.getOrNull()
        }

    private val _allVersions = MutableStateFlow<List<MinecraftVersion>>(emptyList())
    val allVersions = _allVersions.asStateFlow()

    suspend fun refreshVersions(force: Boolean = false) {
        val priority = gameDownloadPriority()
        if (!force && publishedPriority == priority && _allVersions.value.isNotEmpty()) return
        val versions = getVersionManifest(force, priority).versions.mapVersion()
        if (priority == gameDownloadPriority()) {
            _allVersions.value = versions
            publishedPriority = priority
        }
    }

    suspend fun getVersionManifest(force: Boolean = false): VersionManifest =
        getVersionManifest(force, gameDownloadPriority())

    /** The mirror may not have synchronized this version yet. Never substitute a different one. */
    internal suspend fun getVersionEntry(
        versionId: String,
        priority: MirrorPriority = gameDownloadPriority()
    ): VersionManifest.Version? {
        val selected = getVersionManifest(force = false, priority = priority).versions.firstOrNull { it.id == versionId }
        if (selected != null || priority == MirrorPriority.OFFICIAL) return selected
        return getVersionManifest(force = false, priority = MirrorPriority.OFFICIAL)
            .versions.firstOrNull { it.id == versionId }
    }

    private suspend fun getVersionManifest(force: Boolean, priority: MirrorPriority): VersionManifest =
        withContext(Dispatchers.IO) {
            manifestLock.withLock {
                if (!force) manifests[priority]?.let { return@withLock it }
                val file = manifestFile(priority)
                val local = runCatching { parseManifest(AtomicFile(file).readFully().decodeToString()) }.getOrNull()
                val isFresh = file.lastModified() + TimeUnit.DAYS.toMillis(1) >= System.currentTimeMillis()
                val manifest = if (!force && isFresh && local != null) local else try {
                    downloadVersionManifest(file, priority)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Preserve a usable cache if a non-interactive refresh is offline.
                    if (force || local == null) throw e
                    Logger.warning(TAG, "Could not refresh version manifest; using cached list", e)
                    local
                }
                (mergeUnlistVersions(manifest) ?: manifest).also { manifests[priority] = it }
            }
        }

    private fun manifestFile(priority: MirrorPriority): File {
        val official = PathManager.FILE_MINECRAFT_VERSIONS
        return if (priority == MirrorPriority.OFFICIAL) official
        else File(official.parentFile, "minecraft_versions_bmclapi.json")
    }

    private fun parseManifest(rawJson: String): VersionManifest {
        val manifest = GSON.fromJson(rawJson, VersionManifest::class.java)
            ?: throw IOException("Missing Minecraft version manifest")
        if (manifest.versions.isEmpty() || manifest.latest.release.isBlank()) {
            throw IOException("Incomplete Minecraft version manifest")
        }
        return manifest
    }

    private suspend fun downloadVersionManifest(file: File, priority: MirrorPriority): VersionManifest {
        val (rawJson, manifest) = withRetry(TAG, maxRetries = 2) {
            fetchStringFromUrls(URL_MINECRAFT_VERSION_REPOS.mapBMCLMirrorUrls(priority)) { raw ->
                raw to parseManifest(raw)
            }
        }
        val cache = AtomicFile(file)
        val stream = cache.startWrite()
        try {
            stream.write(rawJson.encodeToByteArray())
            cache.finishWrite(stream)
        } catch (e: Exception) {
            cache.failWrite(stream)
            throw e
        }
        return manifest
    }

    /**
     * 尝试从本地合并官方隐藏的版本
     */
    private suspend fun mergeUnlistVersions(
        currentManifest: VersionManifest
    ): VersionManifest? {
        return withContext(Dispatchers.IO) {
            MinecraftVersions::class.java.getResourceAsStream("/assets/game/unlist_versions.json")?.use { input ->
                input.readString()
            }?.let { unlistVersionJson ->
                GSON.fromJson<List<VersionManifest.Version>>(
                    unlistVersionJson,
                    object : TypeToken<List<VersionManifest.Version>>() {}.type
                )
            }?.let { unlistVersions ->
                val versions = currentManifest.versions.toMutableList()
                versions.addAll(unlistVersions)
                versions.sortWith { version, other ->
                    other.releaseTime.compareTo(version.releaseTime)
                }

                currentManifest.copy(
                    versions = versions.toList()
                )
            }
        }
    }
}