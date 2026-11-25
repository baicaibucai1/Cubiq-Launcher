package com.movtery.zalithlauncher.game.addons.mirror

import com.movtery.zalithlauncher.game.download.assets.platform.mcim.mapMCIMMirrorUrls
import com.movtery.zalithlauncher.setting.enums.MirrorSourceType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSelectionTest {
    @Test
    fun automaticPriorityDependsOnMainland() {
        assertEquals(MirrorPriority.MIRROR_FIRST, resolveMirrorPriority(MirrorSourceType.AUTO, true))
        assertEquals(MirrorPriority.OFFICIAL, resolveMirrorPriority(MirrorSourceType.AUTO, false))
    }

    @Test
    fun explicitPriorityOverridesMainland() {
        for (mainland in listOf(true, false)) {
            assertEquals(MirrorPriority.OFFICIAL, resolveMirrorPriority(MirrorSourceType.OFFICIAL, mainland))
            assertEquals(MirrorPriority.MIRROR_FIRST, resolveMirrorPriority(MirrorSourceType.MIRROR, mainland))
        }
    }

    @Test
    fun bmclMapsMetadataAssetsAndMavenWithoutChangingSuffix() {
        val cases = mapOf(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json?x=a%2Fb#release" to
                "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json?x=a%2Fb#release",
            "http://resources.download.minecraft.net/aa/aabb?x=2" to
                "https://bmclapi2.bangbang93.com/assets/aa/aabb?x=2",
            "https://libraries.minecraft.net/com/test/1/test-1.jar" to
                "https://bmclapi2.bangbang93.com/maven/com/test/1/test-1.jar",
            "https://files.minecraftforge.net/maven/net/minecraftforge/forge/maven-metadata.xml" to
                "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/maven-metadata.xml",
            "https://repo.maven.apache.org/maven2/org/example/artifact.jar?download=1" to
                "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/org/example/artifact.jar?download=1"
        )
        for ((official, mirror) in cases) {
            assertEquals(listOf(mirror, official), official.mapBMCLMirrorUrls(MirrorPriority.MIRROR_FIRST))
            assertEquals(listOf(official), official.mapBMCLMirrorUrls(MirrorPriority.OFFICIAL))
        }
    }

    @Test
    fun bmclRejectsLookalikeHostAndPathPrefixes() {
        val unchanged = listOf(
            "https://piston-meta.mojang.com.example.net/mc/game/version_manifest_v2.json",
            "https://files.minecraftforge.net/maven-backup/a.jar",
            "https://example.net/a.jar"
        )
        for (url in unchanged) {
            assertEquals(listOf(url), url.mapBMCLMirrorUrls(MirrorPriority.MIRROR_FIRST))
        }
    }

    @Test
    fun mcimMapsAllSupportedCdnsWithOriginalFallback() {
        val suffix = "/files/123/file%20name.jar?version=1#file"
        for (host in listOf("edge.forgecdn.net", "mediafilez.forgecdn.net", "cdn.modrinth.com")) {
            val official = "https://$host$suffix"
            assertEquals(
                listOf("https://mod.mcimirror.top$suffix", official),
                official.mapMCIMMirrorUrls(MirrorPriority.MIRROR_FIRST)
            )
            assertEquals(listOf(official), official.mapMCIMMirrorUrls(MirrorPriority.OFFICIAL))
        }
    }

    @Test
    fun mcimArrayDeduplicatesWithinAndAcrossMirrorAndOriginals() {
        val edge = "https://edge.forgecdn.net/files/a.jar"
        val mediafilez = "https://mediafilez.forgecdn.net/files/a.jar"
        val modrinth = "https://cdn.modrinth.com/data/b.jar"
        val lookalike = "https://edge.forgecdn.net.example.net/files/a.jar"
        val mirrorA = "https://mod.mcimirror.top/files/a.jar"
        val mirrorB = "https://mod.mcimirror.top/data/b.jar"
        val urls = arrayOf(edge, mediafilez, edge, lookalike, modrinth, mirrorA)

        assertEquals(
            listOf(mirrorA, mirrorB, edge, mediafilez, lookalike, modrinth),
            urls.mapMCIMMirrorUrls(MirrorPriority.MIRROR_FIRST)
        )
        assertEquals(
            listOf(edge, mediafilez, lookalike, modrinth, mirrorA),
            urls.mapMCIMMirrorUrls(MirrorPriority.OFFICIAL)
        )
        assertEquals(listOf(lookalike), lookalike.mapMCIMMirrorUrls(MirrorPriority.MIRROR_FIRST))
    }

    @Test
    fun mirrorNullEmptyAndHttpIoFailureFallBackToOfficial() = runBlocking {
        val responses: List<() -> List<String>?> = listOf(
            { null },
            { emptyList() },
            { throw IOException("HTTP 503") }
        )
        for (respond in responses) {
            val attempts = mutableListOf<SourceType>()
            val result = runMirrorable(
                listOf(
                    MirrorSource(SourceType.BMCLAPI) { attempts.add(SourceType.BMCLAPI); respond() },
                    MirrorSource(SourceType.OFFICIAL) { attempts.add(SourceType.OFFICIAL); listOf("official") }
                ),
                accept = List<String>::isNotEmpty
            )
            assertEquals(listOf("official"), result)
            assertEquals(listOf(SourceType.BMCLAPI, SourceType.OFFICIAL), attempts)
        }
    }

    @Test
    fun successfulMirrorDoesNotCallOfficial() = runBlocking {
        val result = runMirrorable(
            listOf(
                MirrorSource(SourceType.BMCLAPI) { listOf("mirror") },
                MirrorSource(SourceType.OFFICIAL) { error("unexpected official request") }
            ),
            accept = List<String>::isNotEmpty
        )
        assertEquals(listOf("mirror"), result)
    }

    @Test
    fun emptyOfficialResultIsReturnedAfterFallback() = runBlocking {
        val result = runMirrorable(
            listOf(
                MirrorSource<List<String>>(SourceType.BMCLAPI) { null },
                MirrorSource(SourceType.OFFICIAL) { emptyList() }
            ),
            accept = List<String>::isNotEmpty
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun lastOriginExceptionRemainsInTheCauseChain() {
        val originError = IOException("official failed")
        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                runMirrorable<String>(
                    listOf(
                        MirrorSource(SourceType.BMCLAPI) { throw IOException("mirror failed") },
                        MirrorSource(SourceType.OFFICIAL) { throw originError }
                    )
                )
            }
        }
        assertEquals(originError.message, thrown.message)
        // Coroutine stack trace recovery may rethrow a copy with the original as its cause.
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.take(8).any { it === originError })
    }

    @Test
    fun cancellationExceptionDoesNotFallBack() {
        val cancellation = CancellationException("mirror cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runMirrorable(
                    listOf(
                        MirrorSource(SourceType.BMCLAPI) { throw cancellation },
                        MirrorSource(SourceType.OFFICIAL) { error("unexpected official request") }
                    )
                )
            }
        }
        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun cancellingSuspendedMirrorDoesNotCallOfficial() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val attemptedOfficial = CompletableDeferred<Unit>()
        val job = launch {
            runMirrorable(
                listOf(
                    MirrorSource(SourceType.BMCLAPI) { started.complete(Unit); awaitCancellation() },
                    MirrorSource(SourceType.OFFICIAL) { attemptedOfficial.complete(Unit); "official" }
                )
            )
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(attemptedOfficial.isCompleted)
    }
}
