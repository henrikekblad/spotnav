package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        expect("camera_frame_label", "Crop parking spot", "Beskär bild laddplats", "Beskær billede af ladeplads",
            "Beskjær bilde av ladeplass", "Rajaa latauspaikan kuva")
        expect("camera_frame_intro",
            "Drag the selection around the parking spot: move it, or drag a corner. Only what is inside is compared.",
            "Dra markeringen runt laddplatsen: flytta den eller dra i ett hörn. Bara det som är innanför jämförs.",
            "Træk markeringen omkring ladepladsen: flyt den, eller træk i et hjørne. Kun det indenfor sammenlignes.",
            "Dra markeringen rundt ladeplassen: flytt den, eller dra i et hjørne. Bare det som er innenfor, sammenlignes.",
            "Vedä valinta latauspaikan ympärille: siirrä sitä tai vedä kulmasta. Vain valinnan sisältöä verrataan.")
        // Nothing says "frame" (or "ruta") any more: the picture is cropped, the selection dragged.
        for (directory in directories) {
            val camera = Regex("<string name=\"(camera|reference)_[a-z_]+\">(.*?)</string>").findAll(xml(directory))
                .map { it.groupValues[2] }.toList()
            for (text in camera) {
                for (word in listOf("frame", "ruta", "ramme", "rajaus")) {
                    assertFalse("$directory: $text", Regex("\\b$word\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
                }
            }
        }
        expect("camera_frame_whole", "Whole picture", "Hela bilden", "Hele billedet", "Hele bildet", "Koko kuva")
        expect("camera_frame_cropped", "Cropped", "Beskuren", "Beskåret", "Beskåret", "Rajattu")
        // The AI task is chosen in the Home Assistant card alone.
        for (directory in directories) {
            for (gone in listOf("camera_ai_task_label", "camera_ai_task_default", "camera_frame_drawn")) {
                assertFalse("$directory still has $gone", xml(directory).contains("name=\"$gone\""))
            }
        }
    }

    @Test fun aCarsReferencePictureIsWordedAsInTheCard() {
        expect("reference_label", "Reference picture", "Referensbild", "Referencebillede", "Referansebilde", "Vertailukuva")
        expect("reference_title", "Reference picture — %1\$s", "Referensbild — %1\$s", "Referencebillede — %1\$s",
            "Referansebilde — %1\$s", "Vertailukuva — %1\$s")
        expect("reference_intro", "Take the picture while the car is parked at the charger.",
            "Ta bilden när bilen står vid laddaren.", "Tag billedet, mens bilen holder ved laderen.",
            "Ta bildet mens bilen står ved laderen.", "Ota kuva, kun auto on pysäköity laturille.")
        expect("reference_empty", "No picture", "Ingen bild", "Intet billede", "Ikke noe bilde", "Ei kuvaa")
        expect("reference_take_day", "Take day picture", "Ta dagbild", "Tag dagbillede", "Ta dagbilde", "Ota päiväkuva")
        expect("reference_take_night", "Take night picture", "Ta nattbild", "Tag natbillede", "Ta nattbilde", "Ota yökuva")
        expect("reference_retake", "Retake", "Ta om", "Tag igen", "Ta på nytt", "Ota uudelleen")
        expect("reference_close", "Close", "Stäng", "Luk", "Lukk", "Sulje")
        expect("reference_delete", "Delete", "Ta bort", "Slet", "Slett", "Poista")
        // A reference picture is cropped when it is compared, so none is ever out of date: no such line.
        for (directory in directories) {
            for (gone in listOf("reference_stale", "reference_day_picture", "reference_night_picture")) {
                assertFalse("$directory still has $gone", xml(directory).contains("name=\"$gone\""))
            }
        }
        // The dialog's own button was "Take reference picture now"; each slot has its own button now.
        for (directory in directories) {
            assertFalse("$directory still has reference_take", xml(directory).contains("name=\"reference_take\""))
        }
    }
}
