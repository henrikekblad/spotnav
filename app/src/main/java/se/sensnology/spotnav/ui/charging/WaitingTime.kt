package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.planning.PriceWaiting
import java.time.OffsetDateTime

/** Whether the expected publication a waiting line would name is already behind us. */
internal object WaitingTime {
    fun passed(waiting: PriceWaiting, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        !waiting.expectedAt.isAfter(now)
}
