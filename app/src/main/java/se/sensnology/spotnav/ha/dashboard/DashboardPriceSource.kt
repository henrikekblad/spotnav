package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * One electricity price's fees as the dashboard's `fiscal` block states them (the card's `decodeFiscal`): each
 * component's `policy`, its `effective_value` and its `unit`.
 */
internal data class PriceFees(val vat: Component, val tax: Component, val transfer: Component) {
    data class Component(val policy: String, val effective: Double?, val unit: String?)

    companion object {
        /** The fees, or `null` when one of the three components is not readable. */
        fun parse(raw: Any?): PriceFees? {
            val block = raw as? JSONObject ?: return null
            fun component(value: Any?): Component? {
                val item = value as? JSONObject ?: return null
                val policy = item.opt("policy") as? String ?: return null
                val effective = (item.opt("effective_value") as? Number)?.toDouble()?.takeIf { it.isFinite() }
                return Component(policy, effective, item.opt("unit") as? String)
            }
            return PriceFees(
                component(block.opt("vat")) ?: return null,
                component(block.opt("tax")) ?: return null,
                component(block.opt("transfer")) ?: return null
            )
        }
    }
}

/**
 * The dashboard's `price_source` block (Home Assistant 1.22, asked for with the `reads` value `price_source`): the
 * electricity price entry this charger uses, where its area and fees live, how many chargers use it, every entry to
 * choose from when there is more than one, whether this connection may change it, and the same-area question while
 * it is unanswered. Absent while the charger prices with its own settings.
 */
internal data class DashboardPriceSource(
    val entryId: String,
    val title: String,
    val chargerCount: Int,
    /** Every electricity price entry when there is more than one, else empty. */
    val choices: List<Choice>,
    val writable: Boolean,
    /** Same-area entries with different fees not asked about yet (an administrator's answer only), or `null`. */
    val duplicate: Duplicate?
) {
    data class Choice(val entryId: String, val title: String)

    /** One entry of the same-area question, with its fees (`null` where Home Assistant cannot read them). */
    data class DuplicateEntry(val entryId: String, val title: String, val fees: PriceFees?)

    data class Duplicate(val areaId: String, val entries: List<DuplicateEntry>)

    companion object {
        private val KEYS = setOf("entry_id", "title", "charger_count", "choices", "writable", "duplicate")
        private val CHOICE_KEYS = setOf("entry_id", "title")
        private val DUPLICATE_KEYS = setOf("area_id", "entry_ids", "titles", "fees")

        private class Unreadable : Exception()

        private fun bad(): Nothing = throw Unreadable()

        /**
         * The block read strictly, as the card reads it (`validate.ts:priceSourceOrNull`): exactly its keys, every
         * value of its type. Anything else is `null`, as if absent; it never fails the dashboard.
         */
        fun parse(raw: Any?): DashboardPriceSource? {
            val block = raw as? JSONObject ?: return null
            return try {
                exactKeys(block, KEYS)
                val count = block.opt("charger_count")
                val chargerCount = when (count) {
                    is Int -> count
                    is Long -> count.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: bad()
                    // A whole number written with a fraction (`2.0`) is still a whole number, as the card reads it.
                    is Number -> count.toDouble().takeIf { it == Math.floor(it) && it <= Int.MAX_VALUE }?.toInt() ?: bad()
                    else -> bad()
                }.takeIf { it >= 0 } ?: bad()
                val writable = block.opt("writable") as? Boolean ?: bad()
                val choices = list(block, "choices").map { entry ->
                    val item = entry as? JSONObject ?: bad()
                    exactKeys(item, CHOICE_KEYS)
                    Choice(text(item, "entry_id"), text(item, "title"))
                }
                val duplicate = when (val value = block.opt("duplicate")) {
                    JSONObject.NULL -> null
                    is JSONObject -> duplicate(value)
                    else -> bad()
                }
                DashboardPriceSource(text(block, "entry_id"), text(block, "title"), chargerCount, choices, writable, duplicate)
            } catch (_: Unreadable) {
                null
            }
        }

        private fun duplicate(item: JSONObject): Duplicate {
            exactKeys(item, DUPLICATE_KEYS)
            val ids = list(item, "entry_ids").map { (it as? String)?.takeIf { id -> id.isNotEmpty() } ?: bad() }
            val titles = list(item, "titles").map { it as? String ?: bad() }
            val fees = list(item, "fees").map { if (it == JSONObject.NULL) null else PriceFees.parse(it) ?: bad() }
            if (ids.size < 2 || ids.size != titles.size || ids.size != fees.size) bad()
            return Duplicate(text(item, "area_id"), ids.indices.map { DuplicateEntry(ids[it], titles[it], fees[it]) })
        }

        private fun exactKeys(item: JSONObject, keys: Set<String>) {
            if (item.keys().asSequence().toSet() != keys) bad()
        }

        private fun text(item: JSONObject, key: String): String = item.opt(key) as? String ?: bad()

        private fun list(item: JSONObject, key: String): List<Any?> {
            val array = item.opt(key) as? JSONArray ?: bad()
            return (0 until array.length()).map { array.opt(it) }
        }
    }
}
