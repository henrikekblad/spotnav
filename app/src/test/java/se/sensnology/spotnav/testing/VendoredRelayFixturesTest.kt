package se.sensnology.spotnav.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The vendored relay captures are a copy, so they can fall behind. */
class VendoredRelayFixturesTest {
    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "scripts/sync_relay_fixtures.sh").isFile) {
                return File(dir.parentFile, "spotnav-relay/testdata/upstream").takeIf { it.isDirectory }
            }
            dir = dir.parentFile
        }
        return null
    }

    @Test fun theVendoredCopyIsNotEmpty() {
        assertTrue(RelayUpstream.files().any { it.name == "ecb_daily.xml" })
    }

    @Test fun eachVendoredCaptureEqualsTheRelaysWhenItIsPresent() {
        val source = sourceRoot()
        assumeTrue("the relay repository is not next to this one", source != null)
        for (ours in RelayUpstream.files()) {
            val theirs = File(source, ours.name)
            assertTrue("${ours.name} is gone from the relay (run scripts/sync_relay_fixtures.sh)", theirs.isFile)
            assertTrue(
                "${ours.name} differs (run scripts/sync_relay_fixtures.sh)",
                theirs.readBytes().contentEquals(ours.readBytes())
            )
        }
        assertEquals(
            "the relay's captures the script copies",
            source!!.listFiles().orEmpty().map { it.name }.filter { copied(it) }.sorted(),
            RelayUpstream.files().map { it.name }
        )
    }

    /** The names scripts/sync_relay_fixtures.sh copies. */
    private fun copied(name: String): Boolean = name == "ecb_daily.xml"
}
