package se.sensnology.spotnav.app

import se.sensnology.spotnav.R

/** GitHub build: the voluntary GitHub Sponsors link. */
internal object StoreActions {
    /** GitHub Sponsors' own pink, for the heart glyph only. */
    private const val SPONSORS_PINK = 0xFFDB61A2.toInt()

    val action = StoreAction(
        labelRes = R.string.store_action,
        unavailableRes = R.string.store_action_unavailable,
        iconRes = R.drawable.ic_store_action,
        iconTint = SPONSORS_PINK,
        url = Sponsors.URL
    )
}
