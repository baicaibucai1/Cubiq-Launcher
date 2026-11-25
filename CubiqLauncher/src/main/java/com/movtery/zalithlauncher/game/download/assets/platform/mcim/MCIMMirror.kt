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

package com.movtery.zalithlauncher.game.download.assets.platform.mcim

import com.movtery.zalithlauncher.game.addons.mirror.MirrorPriority
import com.movtery.zalithlauncher.game.addons.mirror.orderCandidates
import com.movtery.zalithlauncher.game.addons.mirror.replaceDownloadUrlPrefix
import com.movtery.zalithlauncher.game.addons.mirror.resolveMirrorPriority
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.isChinaMainland

private const val ROOT = "https://mod.mcimirror.top"

private val origins = listOf(
    "https://edge.forgecdn.net",
    "https://mediafilez.forgecdn.net",
    "https://cdn.modrinth.com"
)

internal fun assetPlatformPriority(): MirrorPriority =
    resolveMirrorPriority(AllSettings.assetPlatformSource.getValue(), mainland = isChinaMainland())

private fun String.toMCIMMirrorUrl(): String? =
    origins.firstNotNullOfOrNull { origin -> replaceDownloadUrlPrefix(origin, ROOT) }

fun String.mapMCIMMirrorUrls(priority: MirrorPriority = assetPlatformPriority()): List<String> =
    orderCandidates(this, toMCIMMirrorUrl(), priority)

/** 所有可用的镜像候选优先，其次按原顺序尝试原站。 */
fun Array<String>.mapMCIMMirrorUrls(priority: MirrorPriority = assetPlatformPriority()): List<String> =
    when (priority) {
        MirrorPriority.OFFICIAL -> distinct()
        MirrorPriority.MIRROR_FIRST -> (mapNotNull { it.toMCIMMirrorUrl() } + toList()).distinct()
    }
