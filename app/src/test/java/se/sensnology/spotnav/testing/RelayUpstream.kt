package se.sensnology.spotnav.testing

import java.io.File

/** The vendored copy of the relay's upstream captures (see scripts/sync_relay_fixtures.sh). */
internal object RelayUpstream {
    val root: File = File(
        checkNotNull(RelayUpstream::class.java.getResource("/relay-upstream")) { "no vendored captures" }.toURI()
    )

    fun read(name: String): String = File(root, name).readText()

    /** One of the relay's frozen day files (`testdata/golden`). */
    fun golden(name: String): String = File(
        checkNotNull(RelayUpstream::class.java.getResource("/relay-golden/$name")) { "no vendored $name" }.toURI()
    ).readText()

    fun files(): List<File> = root.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
}
