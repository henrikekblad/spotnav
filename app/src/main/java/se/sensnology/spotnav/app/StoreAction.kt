package se.sensnology.spotnav.app

/**
 * The one link out of the app that Settings shows above its cards. What it is depends on the
 * store the build targets (`-Pstore=github|play`, see app/build.gradle.kts): each store's source
 * directory (`app/src/<store>`) defines [StoreActions] with its own label, glyph and address.
 */
internal class StoreAction(
    val labelRes: Int,
    val unavailableRes: Int,
    val iconRes: Int,
    /** ARGB tint for the glyph, or null for the screen's accent colour. */
    val iconTint: Int?,
    val url: String
)
