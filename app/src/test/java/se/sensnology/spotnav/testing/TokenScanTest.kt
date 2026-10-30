package se.sensnology.spotnav.testing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The pre-handoff scan of the relay's `token_scan_test.go`: skipped unless a token file is named, so the
 * ordinary test run stays offline and needs no key. Run it as
 *
 *     SPOTNAV_ENTSOE_TOKEN_FILE=/path/to/token ./gradlew testDebugUnitTest --tests '*TokenScanTest*'
 *
 * It reports which files hold the key, never the key, its length or a hash of it. `.git`, build output and
 * Gradle's caches are skipped.
 */
class TokenScanTest {
    @Test fun theTreeDoesNotContainTheRealKey() {
        val path = System.getenv("SPOTNAV_ENTSOE_TOKEN_FILE")?.trim().orEmpty()
        assumeTrue("set SPOTNAV_ENTSOE_TOKEN_FILE to search this tree for the real key", path.isNotEmpty())
        val token = File(path).readText().trim()
        // A value this short would match half the tree and prove nothing.
        assertTrue("the token file holds too little to scan for", token.length >= 16)

        var root: File = File(System.getProperty("user.dir")).absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("no project root")
        val skipped = setOf(".git", "build", ".gradle", ".idea")
        val hits = root.walkTopDown()
            .onEnter { it.name !in skipped }
            .filter { it.isFile && it.length() <= 8L * 1024 * 1024 && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { runCatching { it.readText().contains(token) }.getOrDefault(false) }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("the key appears in ${hits.size} files: $hits", hits.isEmpty())
    }
}
