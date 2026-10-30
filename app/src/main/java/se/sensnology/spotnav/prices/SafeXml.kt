package se.sensnology.spotnav.prices

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The upstream XML this app reads (ENTSO-E's documents, the ECB's rates), parsed with the platform's
 * DOM and nothing else: namespace-unaware, elements matched by their local name, and a body that
 * declares a document type refused before it is parsed, so no external entity is ever resolved.
 */
internal object SafeXml {
    /** The document element, or `null` when [body] is not well-formed XML or declares a document type. */
    fun root(body: String): Element? {
        if (body.contains("<!DOCTYPE", ignoreCase = true)) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                isExpandEntityReferences = false
                isCoalescing = true
            }
            // Belt and braces where the runtime knows the feature; the refusal above already holds.
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val builder = factory.newDocumentBuilder()
            // A parse error is a refusal, never a message on stderr.
            builder.setErrorHandler(Quiet)
            builder.parse(InputSource(StringReader(body))).documentElement
        }.getOrNull()
    }

    private object Quiet : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }

    /** An element's name without its prefix: `gesmes:Envelope` is `Envelope`. */
    fun localName(element: Element): String = element.tagName.substringAfterLast(':')

    /** The direct child elements of [parent] named [name] (local name), in document order. */
    fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && localName(node) == name) out.add(node)
        }
        return out
    }

    /** The first direct child named [name], or `null`. */
    fun child(parent: Element, name: String): Element? = children(parent, name).firstOrNull()

    /** The text of the first direct child named [name], or `null` when there is none. */
    fun childText(parent: Element, name: String): String? = child(parent, name)?.textContent
}
