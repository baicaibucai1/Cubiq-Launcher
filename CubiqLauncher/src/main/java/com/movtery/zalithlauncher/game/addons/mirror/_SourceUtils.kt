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

import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

private const val TAG = "SourceUtils"

data class MirrorSource<T>(
    val type: SourceType,
    val block: suspend () -> T?
)

/** 按顺序尝试。镜像没有结果或出错时，继续尝试官方源；不吞掉取消信号。 */
suspend fun <T> runMirrorable(
    sources: List<MirrorSource<T>>,
    accept: (T) -> Boolean = { true }
): T? = withContext(Dispatchers.IO) {
    require(sources.isNotEmpty()) { "No download sources configured" }
    for ((index, source) in sources.withIndex()) {
        ensureActive()
        val isLast = index == sources.lastIndex
        try {
            val result = source.block()
            ensureActive()
            if (isLast || (result != null && accept(result))) return@withContext result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isLast) throw e
            Logger.debug(TAG, "Source ${source.type.displayName} failed!", e)
        }
    }
    throw IOException("Failed to retrieve information from the source!")
}

/** 按“游戏内容下载源”设置重排。官方模式不访问镜像。 */
fun <T> List<MirrorSource<T>>.orderedByGameSourcePreference(): List<MirrorSource<T>> =
    when (gameDownloadPriority()) {
        MirrorPriority.OFFICIAL -> filter { it.type == SourceType.OFFICIAL }
        MirrorPriority.MIRROR_FIRST -> sortedBy { it.type == SourceType.OFFICIAL }
    }
