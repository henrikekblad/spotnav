package se.sensnology.spotnav.testing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The JVM half of the parity check (step A5): what the app's parsers make of the vendored captures here is
 * the text in `device-parity/price_parity.txt`, which the emulator check (`DeviceChecks`) holds Android's own
 * XML and JSON implementations to. `PRICE_PARITY_WRITE=1` writes the file anew after a deliberate change.
 */
class PriceParityTest {
    private val expected = File("src/test/resources/device-parity/price_parity.txt")

    @Test
    fun theJvmReadsTheCapturesAsTheExpectedLinesSay() {
        val lines = PriceParity.lines(
            areasJson = File("src/main/assets/areas.json").readText(),
            capture = { RelayUpstream.read(it) },
            goldenSe4 = RelayUpstream.golden("v1_day_SE4.json")
        )
        val text = lines.joinToString("\n", postfix = "\n")
        if (System.getenv("PRICE_PARITY_WRITE") == "1") expected.writeText(text)
        assertEquals(expected.readText(), text)
    }
}
