package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The camera's words in the five languages, as the Home Assistant card has them. */
class CameraWordingTest {
    private val directories = listOf("values", "values-sv", "values-da", "values-nb", "values-fi")

    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    private fun text(directory: String, name: String): String? =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml(directory))?.groupValues?.get(1)

    private fun expect(name: String, vararg words: String) {
        for ((directory, expected) in directories.zip(words.toList())) {
            assertEquals("$directory $name", expected, text(directory, name)?.replace("\\'", "'"))
        }
    }

    @Test fun theCarLineSaysTheCameraRecognisedTheCar() =
        expect("identify_by_camera", "identified by the camera", "identifierad via kamera", "identificeret via kamera",
            "identifisert via kamera", "tunnistettu kameralla")

    @Test fun theChargersRowsAreNamedAsInTheCard() {
        expect("camera_label", "Camera", "Kamera", "Kamera", "Kamera", "Kamera")
        expect("camera_none", "No camera", "Ingen kamera", "Intet kamera", "Ingen kamera", "Ei kameraa")
        expect("camera_frame_label", "Frame", "Ruta", "Ramme", "Ramme", "Rajaus")
        expect("camera_frame_whole", "Whole picture", "Hela bilden", "Hele billedet", "Hele bildet", "Koko kuva")
        expect("camera_frame_drawn", "Drawn", "Ritad", "Tegnet", "Tegnet", "Piirretty")
        expect("camera_ai_task_label", "AI task", "AI-uppgift", "AI-opgave", "AI-oppgave", "AI-tehtävä")
        expect("camera_ai_task_default", "Home Assistant's default", "Home Assistants standard", "Home Assistants standard",
            "Home Assistants standard", "Home Assistantin oletus")
    }

    @Test fun aCarsReferencePictureIsWordedAsInTheCard() {
        expect("reference_label", "Reference picture", "Referensbild", "Referencebillede", "Referansebilde", "Vertailukuva")
        expect("reference_take", "Take reference picture now", "Ta referensbild nu", "Tag referencebillede nu",
            "Ta referansebilde nå", "Ota vertailukuva nyt")
        expect("reference_take_night", "Take night picture", "Ta nattbild", "Tag natbillede", "Ta nattbilde", "Ota yökuva")
        expect("reference_delete", "Delete", "Ta bort", "Slet", "Slett", "Poista")
        expect("reference_stale", "Taken with another frame – take it again", "Tagen med en annan ruta – ta om",
            "Taget med en anden ramme – tag det igen", "Tatt med en annen ramme – ta det på nytt", "Otettu toisella rajauksella – ota uudelleen")
        // The car's name is filled in by the app: one placeholder in every language.
        for (directory in directories) {
            assertTrue(directory, text(directory, "reference_intro")!!.contains("%1\$s"))
        }
    }
}
