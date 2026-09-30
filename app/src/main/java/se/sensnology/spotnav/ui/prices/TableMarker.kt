package se.sensnology.spotnav.ui.prices

import se.sensnology.spotnav.prices.ChargeCoverage

/**
 * Which of a row's two price columns a cell is: the one fact the charging marker's placement
 * depends on.
 */
internal enum class TableColumn(val markOnRightEdge: Boolean) {
    TODAY(markOnRightEdge = false),
    TOMORROW(markOnRightEdge = true)
}

/** The charging mark's own layout: which parts of a cell's edge are filled. */
internal object TableMarker {
    /**
     * One flag per quarter-hour segment of the cell's edge, **from the top down**: `[true, false,
     * false, false]` for an hour whose first quarter is charged.
     */
    fun segmentsTopDown(coverage: ChargeCoverage): List<Boolean> =
        (0 until coverage.segmentCount).map { coverage.isSelected(it) }
}
