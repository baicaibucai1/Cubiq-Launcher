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

package com.movtery.zalithlauncher.game.addons.mirror

import com.movtery.zalithlauncher.setting.enums.MirrorSourceType

/** 设置折算后的镜像使用策略 */
enum class MirrorPriority {
    /** 只使用官方源 */
    OFFICIAL,
    /** 镜像在前、官方在后 */
    MIRROR_FIRST
}

/**
 * 把三档设置折算成镜像使用策略
 * 自动档依据是否中国大陆静态判定，失败换源由下载引擎在运行时自适应，无需网络探测。
 */
fun resolveMirrorPriority(source: MirrorSourceType, mainland: Boolean): MirrorPriority =
    when {
        source == MirrorSourceType.OFFICIAL -> MirrorPriority.OFFICIAL
        source == MirrorSourceType.MIRROR -> MirrorPriority.MIRROR_FIRST
        mainland -> MirrorPriority.MIRROR_FIRST
        else -> MirrorPriority.OFFICIAL
    }

/** 按偏好排序，且每个候选仅保留一次。 */
internal fun <T : Any> orderCandidates(official: T, mirror: T?, priority: MirrorPriority): List<T> =
    when (priority) {
        MirrorPriority.OFFICIAL -> listOf(official)
        MirrorPriority.MIRROR_FIRST -> listOfNotNull(mirror?.takeUnless { it == official }, official)
    }

/** 只匹配完整域名或路径前缀，保留原 URL 的剩余路径和查询参数。 */
internal fun String.replaceDownloadUrlPrefix(origin: String, mirror: String): String? {
    if (!startsWith(origin)) return null
    val boundary = getOrNull(origin.length)
    if (boundary != null && boundary != '/' && boundary != '?' && boundary != '#') return null
    return mirror + substring(origin.length)
}
