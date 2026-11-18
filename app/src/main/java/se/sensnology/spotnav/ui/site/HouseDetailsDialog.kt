package se.sensnology.spotnav.ui.site

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ui.common.ViewScope
import java.time.LocalDate
import java.util.Locale

/**
 * The house's details sheet, opened from the "i" in the house page's header (the card's history dialog): the same period
 * picker as the boxes, which it shares ([HouseHistoryStore.choice]: a period chosen here is the page's too), and under it
 * every section of [HouseDetails] always open, never behind a "Show details". It reads the period through the page's
 * store, so a newer answer redraws it ([refresh]).
 */
internal class HouseDetailsDialog(
    private val scope: ViewScope,
    private val store: HouseHistoryStore,
    private val text: SiteText,
    private val colours: SiteColours,
    private val locale: Locale,
    private val house: () -> House?,
    private val today: () -> LocalDate,
    /** The shared picker moved: the page reads again and redraws its boxes (and this sheet, through [refresh]). */
    private val onPeriod: (PeriodChoice) -> Unit
) {
    private val words = text.words
    private var dialog: AlertDialog? = null
    private val picker = PeriodPickerView(scope, words, locale) { onPeriod(it) }
    private val content = LinearLayout(scope.context).apply { orientation = LinearLayout.VERTICAL }
    private var selected = HashMap<String, String?>()

    val isOpen: Boolean get() = dialog?.isShowing == true

    fun open() {
        if (isOpen) return
        selected = HashMap()
        (picker.parent as? ViewGroup)?.removeView(picker)
        val body = LinearLayout(scope.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(scope.dp(16), scope.dp(4), scope.dp(16), scope.dp(8))
            addView(picker, wide())
            addView(content, wide().apply { topMargin = scope.dp(8) })
        }
        dialog = AlertDialog.Builder(scope.context)
            .setTitle(words.say("hist.title.house"))
            .setView(ScrollView(scope.context).apply { addView(body) })
            .setPositiveButton(scope.t(android.R.string.ok), null)
            .create().also { it.show() }
        store.request(store.choice, needsHistory = true, force = false)
        refresh()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** The store has a newer answer, or the period moved: draw the sheet again. */
    fun refresh() {
        if (!isOpen) return
        val now = house()
        picker.show(store.choice, today())
        content.removeAllViews()
        val history = when (val loaded = store.history(store.choice)) {
            null, Load.Loading -> return say("hist.loading")
            is Load.Failed -> return say(if (loaded.unsupported) "hist.unsupported" else "hist.failed")
            is Load.Ready -> loaded.value
        }
        for (section in HouseDetails.sections(text, locale, history, now)) addSection(section, history)
    }

    private fun say(key: String) {
        content.addView(note(words.say(key)))
    }

    private fun addSection(section: DetailSection, history: HouseHistory) {
        val box = LinearLayout(scope.context).apply {
            orientation = LinearLayout.VERTICAL
            tag = section.key
            setPadding(scope.dp(12), scope.dp(10), scope.dp(12), scope.dp(10))
            background = GradientDrawable().apply {
                setColor(scope.cardBackground)
                cornerRadius = scope.dp(10).toFloat()
            }
        }
        if (section.title.isNotEmpty()) {
            box.addView(TextView(scope.context).apply {
                textSize = 16f
                setTextColor(scope.dark)
                typeface = MEDIUM
                this.text = section.title
            }, wide())
        }
        section.blocks.forEachIndexed { index, block -> box.addView(view(section, block), wide().apply { topMargin = scope.dp(if (index == 0) 6 else 6) }) }
        content.addView(box, wide().apply { bottomMargin = scope.dp(10) })
    }

    private fun view(section: DetailSection, block: DetailBlock): View = when (block) {
        is DetailBlock.Bars -> bars(section, block)
        is DetailBlock.Figure -> row(block.name, block.value, block.muted)
        is DetailBlock.Note -> note(block.text)
        is DetailBlock.Headline -> TextView(scope.context).apply {
            textSize = 15f
            setTextColor(scope.dark)
            typeface = MEDIUM
            this.text = block.text
        }
        is DetailBlock.Share -> share(block)
        is DetailBlock.Table -> table(block)
        is DetailBlock.Ways -> ways(block)
        is DetailBlock.Sources -> sources(block)
    }

    /** The cost bars: the filled cost, the hatched saving and the hatched extra, in the card's colours. */
    private fun bars(section: DetailSection, block: DetailBlock.Bars): View {
        val view = StackedBarsView(scope, colours)
        val grid = colours.of(Tone.GRID)
        val warning = 0xFFFFA600.toInt()
        val series = block.series.map { BarSeries(it.key, it.label, if (it.key == HouseDetails.EXTRA) warning else grid, hatched = it.hatched) }
        view.onSelect = { selected[section.key] = it }
        view.show(
            BarsSpec(
                label = block.label, bars = block.bars, series = series.ifEmpty { listOf(BarSeries(HouseDetails.USE, "", grid)) },
                scale = block.scale, hint = block.hint, selected = selected[section.key], toggles = false
            )
        )
        return view
    }

    private fun row(name: String, value: String, muted: Boolean) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(scope.dark)
            this.text = name
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(if (muted) scope.muted else scope.dark)
            typeface = MEDIUM
            gravity = Gravity.END
            this.text = value
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = scope.dp(12) })
    }

    private fun note(sentence: String) = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(scope.muted)
        text = sentence
    }

    /** A way's share: its name (with the chosen mode marked), a bar of 0 to 100 % and the figure. */
    private fun share(block: DetailBlock.Share) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(scope.dark)
            text = block.inForce?.let { "${block.name} · $it" } ?: block.name
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        val filled = (block.percent ?: 0.0).coerceIn(0.0, 100.0).toFloat()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(SiteColours.mix(scope.muted, scope.cardBackground, 0.2f))
                cornerRadius = scope.dp(4).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (filled > 0f) addView(View(context).apply {
                background = GradientDrawable().apply { setColor(colours.of(Tone.BATTERY)); cornerRadius = scope.dp(4).toFloat() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, filled))
            if (filled < 100f) addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f - filled))
        }, LinearLayout.LayoutParams(0, scope.dp(8), 1f).apply { marginStart = scope.dp(8); marginEnd = scope.dp(8) })
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(if (block.muted) scope.muted else scope.dark)
            typeface = MEDIUM
            gravity = Gravity.END
            this.text = block.figure
        }, LinearLayout.LayoutParams(scope.dp(if (block.muted) 130 else 56), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A table of cells in equal columns (the first wider); a cell of the table's [DetailBlock.Table.mutedCells] muted. */
    private fun table(block: DetailBlock.Table) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(cells(block.head, head = true))
        block.rows.forEachIndexed { r, row ->
            addView(cells(row, muted = block.mutedCells.filter { it.first == r }.map { it.second }.toSet()), wide().apply { topMargin = scope.dp(4) })
        }
    }

    private fun cells(values: List<String>, head: Boolean = false, muted: Set<Int> = emptySet()) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        values.forEachIndexed { i, value ->
            addView(TextView(context).apply {
                textSize = if (head) 12f else 13.5f
                setTextColor(if (head || i in muted) scope.muted else scope.dark)
                if (head) typeface = MEDIUM
                gravity = if (i == 0) Gravity.START else Gravity.END
                this.text = value
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (i == 0) 1.6f else 1f))
        }
    }

    /** The ways' figures: each way's name on a line of its own and its cells under the table's head, so it fits a phone. */
    private fun ways(block: DetailBlock.Ways) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(cells(block.head.mapIndexed { i, s -> s }, head = true).also { (it.getChildAt(0) as TextView).gravity = Gravity.START })
        for ((name, figures) in block.groups) {
            addView(TextView(context).apply {
                textSize = 14f
                setTextColor(scope.dark)
                typeface = MEDIUM
                this.text = name
            }, wide().apply { topMargin = scope.dp(8) })
            addView(cells(figures).also { (it.getChildAt(0) as TextView).gravity = Gravity.START })
        }
    }

    private fun sources(block: DetailBlock.Sources) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(scope.dark)
            typeface = MEDIUM
            this.text = block.heading
        }, wide().apply { topMargin = scope.dp(6) })
        for (fact in block.facts) addView(note("• $fact"), wide().apply { topMargin = scope.dp(2) })
        for (line in block.notes) addView(note(line), wide().apply { topMargin = scope.dp(6) })
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private companion object {
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
