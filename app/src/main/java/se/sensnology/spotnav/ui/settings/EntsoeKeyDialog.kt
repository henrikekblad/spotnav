package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.prices.LegacyPriceUser
import se.sensnology.spotnav.prices.PriceRepository
import se.sensnology.spotnav.prices.entsoe.EntsoeKey
import se.sensnology.spotnav.prices.entsoe.EntsoeKeyStore
import se.sensnology.spotnav.prices.entsoe.KeyCheck
import se.sensnology.spotnav.prices.entsoe.KeyState
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine

/**
 * The ENTSO-E key: the one setting an unpaired phone needs for prices (design 5.1). One dialog with the
 * four-step how-to (the site and the e-mail tappable), a hidden field to paste the key in, Save (which
 * checks the key with ENTSO-E first) and Remove key. Someone who had prices from SpotNav's server before
 * sees why a key is needed now, once, before the how-to (5.3).
 */
internal class EntsoeKeyDialog(scope: ViewScope) : ViewScope(scope) {
    private val keys = EntsoeKeyStore.forContext(applicationContext)
    private val flags = SharedPreferencesKeyValueStore(applicationContext, LegacyPriceUser.PREFS)

    /** Open the how-to for [areaId] (the area the key is checked against); [changed] runs after a save or a removal. */
    fun open(areaId: String?, changed: () -> Unit) {
        if (StandalonePrice.explainFirst(LegacyPriceUser.ensure(applicationContext), LegacyPriceUser.whyShown(flags))) {
            explain { howTo(areaId, changed) }
        } else {
            howTo(areaId, changed)
        }
    }

    /** Why a key now: shown once, then the how-to. */
    private fun explain(then: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(t(R.string.entsoe_why_title))
            .setMessage(t(R.string.entsoe_why_body))
            .setPositiveButton(t(R.string.entsoe_why_continue)) { _, _ -> then() }
            .setOnDismissListener { LegacyPriceUser.markWhyShown(flags) }
            .show()
    }

    private fun howTo(areaId: String?, changed: () -> Unit) {
        val state = keys.state()
        val body = editorBody()
        if (state == KeyState.REJECTED) {
            body.addView(TextView(context).apply {
                text = t(R.string.price_need_rejected)
                textSize = 14f
                setTextColor(ERROR_RED)
                setPadding(0, 0, 0, dp(8))
            })
        }
        listOf(R.string.entsoe_howto_1, R.string.entsoe_howto_2, R.string.entsoe_howto_3, R.string.entsoe_howto_4)
            .forEachIndexed { index, step -> body.addView(step(index + 1, t(step))) }
        keys.masked()?.let { masked ->
            body.addView(TextView(context).apply {
                text = t(R.string.entsoe_key_saved, masked)
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(10), 0, 0)
            })
        }
        val field = EditText(context).apply {
            hint = t(R.string.entsoe_key_row)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // A secret: never kept across a re-creation of the screen, never offered to the keyboard's memory.
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setSingleLine()
        }
        body.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        val checking = TextView(context).apply {
            text = t(R.string.entsoe_key_checking)
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        body.addView(checking)
        val error = errorLine().also { body.addView(it) }
        val builder = AlertDialog.Builder(context)
            .setTitle(t(R.string.entsoe_howto_title))
            .setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(t(android.R.string.cancel), null)
            .setPositiveButton(t(R.string.paired_save), null)
        if (state != KeyState.NONE) builder.setNeutralButton(t(R.string.entsoe_key_remove), null)
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            keys.remove()
            PriceRepository.clearMemoryCache()
            dialog.dismiss()
            changed()
        }
        val save: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val key = EntsoeKey(field.text.toString())
            error.visibility = View.GONE
            if (key.isEmpty) {
                say(error, t(R.string.entsoe_key_empty))
                return@setOnClickListener
            }
            save.isEnabled = false
            field.isEnabled = false
            checking.visibility = View.VISIBLE
            Thread {
                val check = PriceRepository.checkEntsoeKey(applicationContext, key, areaId)
                val saved = keys.saveChecked(key, check)
                if (saved) PriceRepository.clearMemoryCache()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    checking.visibility = View.GONE
                    when {
                        saved -> {
                            dialog.dismiss()
                            changed()
                        }
                        else -> {
                            save.isEnabled = true
                            field.isEnabled = true
                            say(error, t(if (check == KeyCheck.REJECTED) R.string.entsoe_key_not_accepted else R.string.entsoe_key_not_saved))
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun say(line: TextView, text: String) {
        line.text = text
        line.visibility = View.VISIBLE
    }

    /** One step of the how-to: "1. …", with the site and the e-mail address tappable. */
    private fun step(number: Int, text: String): TextView {
        val shown = SpannableString("$number. $text")
        link(shown, SITE_NAME) { launch(Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL))) }
        val mail = { launch(mailIntent()) }
        link(shown, MAIL_ADDRESS, mail)
        link(shown, MAIL_SUBJECT, mail)
        return TextView(context).apply {
            this.text = shown
            textSize = 15f
            setTextColor(dark)
            setLinkTextColor(accent)
            setPadding(0, dp(4), 0, dp(4))
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun link(text: SpannableString, part: String, onTap: () -> Unit) {
        val start = text.indexOf(part)
        if (start < 0) return
        text.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = onTap()
            override fun updateDrawState(paint: TextPaint) {
                super.updateDrawState(paint)
                paint.color = accent
            }
        }, start, start + part.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * The request to ENTSO-E, ready to send: their address, the subject they ask for, and a message in
     * English (ENTSO-E's language) that leaves the account's address for the person to type.
     */
    private fun mailIntent() = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(MAIL_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, MAIL_SUBJECT)
        putExtra(Intent.EXTRA_TEXT, MAIL_BODY)
    }

    /** No browser or mail app: nothing happens, as a tap on any link without one. */
    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    internal companion object {
        const val SITE_NAME = "transparency.entsoe.eu"
        const val SITE_URL = "https://transparency.entsoe.eu/"
        const val MAIL_ADDRESS = "transparency@entsoe.eu"
        const val MAIL_SUBJECT = "Restful API access"
        const val MAIL_BODY = "Hello,\n\nPlease give my account access to the Restful API.\n\nThe e-mail address of my account: \n"
        private const val ERROR_RED = 0xFFD65C5C.toInt()
    }
}
