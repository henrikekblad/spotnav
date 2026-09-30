package se.sensnology.spotnav.ui.common

/**
 * What a row's value is, and therefore how it reads: one answer, because two is how a blue value
 * that does nothing appears.
 */
internal enum class ValueCue {
    /** A tap opens the control that edits this. Accent. */
    EDITABLE,

    /** A fact or a computed result: nothing to open, and nothing to say about why. */
    READ_ONLY,

    /**
     * A control this car or charger would offer, in a state that cannot use it -- the car reports
     * no limit to write to, the integration has not advertised the capability, a write is already
     * on its way. Muted.
     */
    UNAVAILABLE;

    /** Whether a tap does something: what colour and behaviour are both set from. */
    val actionable: Boolean get() = this == EDITABLE

    companion object {
        /**
         * [editable] is "this row has a control behind it at all" -- a row that does not is a fact,
         * whatever [available] says: there is no unavailable control on a row that never had one.
         */
        fun of(editable: Boolean, available: Boolean = true): ValueCue = when {
            !editable -> READ_ONLY
            available -> EDITABLE
            else -> UNAVAILABLE
        }
    }
}
