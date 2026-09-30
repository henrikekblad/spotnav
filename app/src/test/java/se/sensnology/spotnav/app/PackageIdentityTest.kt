package se.sensnology.spotnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.BuildConfig
import se.sensnology.spotnav.widget.PriceWidgetProvider
import java.io.File

/** The app's technical identity, as the files outside Kotlin have to agree about it. */
class PackageIdentityTest {
    /** The application's root package: what every other copy has to name. */
    private val codePackage = "se.sensnology.spotnav"

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("${file.absolutePath} must exist", file.isFile)
        return file.readText()
    }

    @Test
    fun theApplicationIdIsThePackageTheCodeIsIn() {
        // One identity, not two that happen to look alike: the id decides which app this is on the
        // device, and the package is what the code calls itself.
        assertEquals(codePackage, BuildConfig.APPLICATION_ID)
    }

    @Test
    fun theWidgetsConfigurationScreenIsThisAppsOwnClass() {
        for (file in listOf(
            "src/main/res/xml/price_widget_info.xml",
            "src/main/res/xml-v31/price_widget_info.xml"
        )) {
            val configure = Regex("android:configure=\"([^\"]+)\"")
                .find(read(file))?.groupValues?.get(1)
            assertEquals("$file names the screen the widget opens", "$codePackage.ui.WidgetConfigActivity", configure)
            assertNotNull("$configure is a class in this app", Class.forName(configure))
        }
    }

    @Test
    fun theProvidersBroadcastActionsAreDeclaredInTheManifestAndAreThisAppsOwn() {
        val declared = Regex("<action android:name=\"([^\"]+)\" ?/>")
            .findAll(read("src/main/AndroidManifest.xml"))
            .map { it.groupValues[1] }
            .toSet()

        for (action in listOf(
            PriceWidgetProvider.ACTION_REFRESH,
            PriceWidgetProvider.ACTION_PUBLICATION_CHECK
        )) {
            assertTrue("the manifest declares $action", action in declared)
            // A package-derived action that kept the old package would be received by nobody --
            // and, with both builds installed, by the other app.
            assertTrue("$action belongs to this app's own package", action.startsWith("$codePackage."))
        }
    }

    @Test
    fun everyComponentTheManifestDeclaresIsAClassInThisApp() {
        val components = Regex("android:name=\"(\\.[^\"]+)\"")
            .findAll(read("src/main/AndroidManifest.xml"))
            .map { it.groupValues[1] }
            .toList()
        assertTrue("the manifest declares its activities and receiver", components.size >= 3)
        for (component in components) {
            assertNotNull("$component is a class in this app", Class.forName(codePackage + component))
        }
    }

    @Test
    fun noWebLinkCanReachTheAppOrRewriteAProfile() {
        // Pairing goes through the approval flow only.
        val manifest = read("src/main/AndroidManifest.xml")
        assertTrue(!manifest.contains("BROWSABLE"))
        assertTrue(!manifest.contains("android:scheme"))
    }

    @Test
    fun everyKotlinSourceIsInThePackageItsDirectoryNames() {
        // A file left in the old directory, or a package line the move missed, is a second package
        // in the module: Kotlin itself only warns about the mismatch, and the Android build
        // resolves `R` from the namespace, so it can stay hidden.
        val sources = File("src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the source tree must be walkable from the module directory", sources.size > 100)

        for (file in sources) {
            val path = file.invariantSeparatorsPath
            assertTrue("$path is under a source set's java directory", "/java/" in path)
            val expected = path.substringAfter("/java/").substringBeforeLast('/').replace('/', '.')
            val declared = Regex("^package (.+)$", RegexOption.MULTILINE)
                .find(file.readText())?.groupValues?.get(1)?.trim()
            assertEquals("$path declares the package its directory is in", expected, declared)
        }
    }
}
