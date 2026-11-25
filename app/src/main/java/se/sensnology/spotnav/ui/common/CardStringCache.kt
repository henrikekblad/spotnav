package se.sensnology.spotnav.ui.common

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import org.json.JSONObject
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.client.CardStringsRead
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The card's words fetched from each Home Assistant (`get_strings`), by instance and language: held in memory for this
 * run and kept in [folder], one file per instance and language with its hash, so a later start uses them at once,
 * offline too. The file keeps the words as fetched; [CardText.accepted] decides at every load which may be shown, so an
 * app update that knows more keys shows them without a new fetch. [english] is the app's own English text of a key.
 */
internal class CardStringCache(private val folder: File, private val english: (String) -> String?) {
    private class Held(val hash: String, val words: Map<String, String>)

    private val held = ConcurrentHashMap<String, Held>()
    private val read = ConcurrentHashMap.newKeySet<String>()

    /** The words that may be shown for [instance] in [language], `null` while none are held. */
    fun words(instance: String, language: String): Map<String, String>? = entry(instance, language)?.words

    /** The hash of the words held for [instance] in [language], to ask whether they are still current. */
    fun hash(instance: String, language: String): String? = entry(instance, language)?.hash

    /**
     * Keep what Home Assistant answered for [language]. Words of another language (an older or newer Home Assistant
     * without this one: it answers English) are not kept: the app's own words in [language] are better.
     */
    fun remember(instance: String, language: String, words: CardStringsRead.Outcome.Words): Boolean {
        if (words.language != language) return false
        held[key(instance, language)] = Held(words.hash, CardText.accepted(words.words, english))
        read += key(instance, language)
        runCatching {
            folder.mkdirs()
            val file = file(instance, language)
            val partial = File(folder, "${file.name}.part")
            partial.writeText(
                JSONObject().put("hash", words.hash).put("language", language).put("strings", JSONObject(words.words)).toString(),
                Charsets.UTF_8
            )
            if (!partial.renameTo(file)) partial.delete()
        }
        return true
    }

    private fun entry(instance: String, language: String): Held? {
        val key = key(instance, language)
        held[key]?.let { return it }
        if (read.add(key)) load(instance, language)?.let { held[key] = it }
        return held[key]
    }

    private fun load(instance: String, language: String): Held? = runCatching {
        val file = file(instance, language)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val hash = json.optString("hash").takeIf { it.isNotBlank() } ?: return null
        if (json.optString("language") != language) return null
        val strings = json.optJSONObject("strings") ?: return null
        val words = HashMap<String, String>(strings.length())
        for (key in strings.keys()) (strings.opt(key) as? String)?.let { words[key] = it }
        Held(hash, CardText.accepted(words, english))
    }.getOrNull()

    private fun key(instance: String, language: String) = "$instance|$language"

    private fun file(instance: String, language: String) = File(folder, "${digest(instance)}-$language.json")

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    companion object {
        /** One Home Assistant, as its address names it: the words of two chargers or homes on it are the same. */
        fun instance(baseUrl: String): String = baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
    }
}

/**
 * When to ask a Home Assistant for its words, per instance and language: once per run when it answered (words,
 * unchanged, or a Home Assistant without `get_strings`), again after [RETRY_MS] when it did not answer. Pure.
 */
internal class CardStringAsks(private val retryMs: Long = RETRY_MS) {
    private val answered = ConcurrentHashMap.newKeySet<String>()
    private val tried = ConcurrentHashMap<String, Long>()

    /** Whether to ask now; claims the ask, so two screens at once ask once. */
    fun claim(key: String, now: Long): Boolean {
        if (key in answered) return false
        var claimed = false
        tried.compute(key) { _, last -> if (last == null || now - last >= retryMs) { claimed = true; now } else last }
        return claimed
    }

    fun outcome(key: String, outcome: CardStringsRead.Outcome) {
        if (outcome !is CardStringsRead.Outcome.Later) answered += key
    }

    companion object {
        const val RETRY_MS = 5 * 60_000L
    }
}

/**
 * The app's side of the card's words from Home Assistant: which instance the screen on display belongs to ([shown]),
 * the words held for it, and the fetch after a screen is shown (on its own thread, never the screen's). Texts shown
 * without a connection to a paired Home Assistant (pairing, the setup before it, the app's own notices, widgets) use
 * the app's own words: they never ask for these.
 */
internal object CardStringSync {
    private const val TAG = "CardStringSync"

    /** The instance ([CardStringCache.instance]) the screen on display belongs to; `null` for none. */
    @Volatile var shown: String? = null

    @Volatile private var cache: CardStringCache? = null
    private val asks = CardStringAsks()

    init {
        CardText.log = { message -> runCatching { Log.w(TAG, message) } }
    }

    fun cache(context: Context): CardStringCache = cache ?: synchronized(this) {
        cache ?: run {
            val app = context.applicationContext
            val english = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) })
            CardStringCache(File(app.filesDir, "card_strings")) { key ->
                CardStrings.IDS[key]?.let { id -> runCatching { english.resources.getString(id) }.getOrNull() }
            }.also { cache = it }
        }
    }

    /** The words that may be shown for [instance] in the app's language, `null` for none (the app's own are used). */
    fun words(context: Context, instance: String?): Map<String, String>? =
        instance?.let { cache(context).words(it, AppLanguageSettings.language(context)) }

    /**
     * A screen of [baseUrl]'s Home Assistant is shown: its words are the ones used from now on, and they are asked for
     * through [settings] (a paired webhook) unless they were this run. `null` [baseUrl]: no paired Home Assistant.
     */
    fun show(context: Context, baseUrl: String?, settings: HomeAssistantSettings?) {
        val instance = baseUrl?.takeIf { it.isNotBlank() }?.let(CardStringCache::instance)
        shown = instance
        if (instance == null || settings == null || !settings.configured) return
        val app = context.applicationContext
        val language = AppLanguageSettings.language(app)
        val key = "$instance|$language"
        if (!asks.claim(key, System.currentTimeMillis())) return
        Thread({
            val held = cache(app)
            val outcome = HomeAssistantClient.cardStrings(settings, language, held.hash(instance, language))
            if (outcome is CardStringsRead.Outcome.Words) held.remember(instance, language, outcome)
            asks.outcome(key, outcome)
        }, "card-strings").start()
    }
}
