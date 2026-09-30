package se.sensnology.spotnav.testing

import org.json.JSONObject
import java.io.File

/** The vendored copy of Home Assistant's contract fixtures (see scripts/sync_ha_fixtures.sh). */
internal object HaFixtures {
    val root: File = File(
        checkNotNull(HaFixtures::class.java.getResource("/ha-fixtures")) { "no vendored fixtures" }.toURI()
    )

    fun files(dir: String): List<File> =
        File(root, dir).listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }

    fun json(path: String): JSONObject = JSONObject(File(root, path).readText())

    /** Every status code in the vendored codes fixture, with its declared param names and tone. */
    fun statusCodes(): Map<String, Pair<List<String>, String>> {
        val table = json("codes/dashboard_codes.json").getJSONObject("status_codes")
        return table.keys().asSequence().associateWith { code ->
            val entry = table.getJSONObject(code)
            val params = entry.getJSONArray("params")
            (0 until params.length()).map { params.getString(it) } to entry.getString("tone")
        }
    }
}
