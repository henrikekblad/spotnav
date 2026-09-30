package se.sensnology.spotnav.ui.charging

/** Where the two object cards go: side by side in one row, or stacked. */
object CardLayout {
    /**
     * The smallest screen width, in dp, where two object cards side by side still read as cards.
     */
    const val SIDE_BY_SIDE_MIN_WIDTH_DP = 600

    /** Whether [screenWidthDp] is wide enough for the cards to sit in a row. */
    fun sideBySide(screenWidthDp: Int): Boolean = screenWidthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP
}
