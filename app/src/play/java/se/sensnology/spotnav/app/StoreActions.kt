package se.sensnology.spotnav.app

import se.sensnology.spotnav.R

/** Play build: a plain link to the source code and issue tracker, nothing else. */
internal object StoreActions {
    const val REPOSITORY_URL = "https://github.com/henrikekblad/spotnav"

    val action = StoreAction(
        labelRes = R.string.store_action,
        unavailableRes = R.string.store_action_unavailable,
        iconRes = R.drawable.ic_store_action,
        iconTint = null,
        url = REPOSITORY_URL
    )
}
