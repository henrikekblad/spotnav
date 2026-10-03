package se.sensnology.spotnav.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExportProviderTest {
    @Test fun aPlainFileNameIsKeptAndAnythingThatCouldEscapeTheFolderIsReplaced() {
        assertEquals("spotnav-sessions-2026-09-01-2026-09-30.csv", CsvExportProvider.safeName("spotnav-sessions-2026-09-01-2026-09-30.csv"))
        for (bad in listOf(null, "", "../x.csv", "a/b.csv", ".hidden", "name with space.csv", "x".repeat(101))) {
            assertEquals("$bad", "spotnav-sessions.csv", CsvExportProvider.safeName(bad))
        }
    }
}
