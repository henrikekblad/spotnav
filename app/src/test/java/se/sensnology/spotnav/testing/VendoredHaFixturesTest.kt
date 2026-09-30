package se.sensnology.spotnav.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The vendored fixtures are a copy, so they can fall behind. */
class VendoredHaFixturesTest {
    private val vendored = listOf("webhook", "dashboard", "settings/v1", "site_settings/v1", "vehicle/v1", "codes")

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "scripts/sync_ha_fixtures.sh").isFile) {
                return File(dir.parentFile, "elpris-home-assistant/tests/fixtures").takeIf { it.isDirectory }
            }
            dir = dir.parentFile
        }
        return null
    }

    @Test fun theVendoredCopyIsNotEmpty() {
        assertTrue(HaFixtures.files("dashboard").size >= 10)
        assertTrue(HaFixtures.files("webhook").any { it.name == "dashboard.json" })
        assertTrue(File(HaFixtures.root, "codes/dashboard_codes.json").isFile)
        assertTrue(HaFixtures.files("settings/v1").any { it.name == "success.json" })
        assertTrue(HaFixtures.files("site_settings/v1").any { it.name == "success.json" })
        assertTrue(HaFixtures.files("vehicle/v1").any { it.name == "update_vehicle_success.json" })
    }

    @Test fun theVendoredCopyEqualsTheHomeAssistantFixturesWhenTheyArePresent() {
        val source = sourceRoot()
        assumeTrue("the Home Assistant repository is not next to this one", source != null)
        for (part in vendored) {
            val theirs = File(source, part).walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(File(source, part)).path to it.readBytes() }
            val ours = File(HaFixtures.root, part).walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(File(HaFixtures.root, part)).path to it.readBytes() }
            // Only the parts the script copies: a single file for `codes`.
            val expected = if (part == "codes") theirs.filterKeys { it == "dashboard_codes.json" } else theirs
            assertEquals("$part: the same files (run scripts/sync_ha_fixtures.sh)", expected.keys, ours.keys)
            for ((name, bytes) in expected) {
                assertTrue("$part/$name differs (run scripts/sync_ha_fixtures.sh)", bytes.contentEquals(ours.getValue(name)))
            }
        }
    }
}
