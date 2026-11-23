package com.officesuite.app.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Helpers for Office Open XML packages (.docx / .xlsx): zip I/O, DOM parsing,
 * relationships and content types. Pure JVM code (no Android imports).
 */
object OoXml {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    const val REL_OFFICE_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"

    fun readZip(file: File): LinkedHashMap<String, ByteArray> =
        FileInputStream(file).use { readZip(it) }

    fun readZip(input: InputStream): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) map[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        if (map.isEmpty()) throw IllegalArgumentException("Not a valid Office file (empty or not a zip archive)")
        return map
    }

    fun writeZip(entries: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { z ->
            val names = entries.keys.sortedBy { if (it == "[Content_Types].xml") 0 else 1 }
            for (name in names) {
                z.putNextEntry(ZipEntry(name))
                z.write(entries.getValue(name))
                z.closeEntry()
            }
        }
    }

    fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        writeZip(entries, bos)
        return bos.toByteArray()
    }

    fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun parse(xml: String): Document = parse(xml.toByteArray(Charsets.UTF_8))

    fun serialize(doc: Document): ByteArray {
        try {
            doc.xmlStandalone = true
        } catch (_: Exception) {
        }
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.INDENT, "no")
        val bos = ByteArrayOutputStream()
        t.transform(DOMSource(doc), StreamResult(bos))
        return bos.toByteArray()
    }

    /** Path of the main part (e.g. word/document.xml or xl/workbook.xml) from _rels/.rels. */
    fun mainPart(zip: Map<String, ByteArray>, fallback: String): String {
        val rels = zip["_rels/.rels"] ?: return fallback
        return try {
            val doc = parse(rels)
            doc.documentElement.kids()
                .firstOrNull { it.getAttribute("Type") == REL_OFFICE_DOC }
                ?.getAttribute("Target")?.trimStart('/')
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /** "word/document.xml" -> "word/_rels/document.xml.rels" */
    fun relsPathFor(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        val name = part.substringAfterLast('/')
        return if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
    }

    /** Resolve a relationship target relative to the source part's folder. */
    fun resolveTarget(sourcePart: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = sourcePart.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in target.split('/')) {
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    data class Rel(val id: String, val type: String, val target: String)

    fun readRels(zip: Map<String, ByteArray>, relsPath: String): List<Rel> {
        val bytes = zip[relsPath] ?: return emptyList()
        return try {
            parse(bytes).documentElement.kids().filter { it.localName == "Relationship" }.map {
                Rel(it.getAttribute("Id"), it.getAttribute("Type"), it.getAttribute("Target"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Adds a relationship and returns its new id. Creates the .rels part if needed. */
    fun addRel(zip: MutableMap<String, ByteArray>, relsPath: String, type: String, target: String): String {
        val doc = zip[relsPath]?.let { parse(it) }
            ?: parse("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"$PKG_REL\"/>")
        val root = doc.documentElement
        val used = root.kids().map { it.getAttribute("Id") }.toSet()
        var n = used.size + 1
        while ("rId$n" in used) n++
        val id = "rId$n"
        val el = doc.createElementNS(PKG_REL, "Relationship")
        el.setAttribute("Id", id)
        el.setAttribute("Type", type)
        el.setAttribute("Target", target)
        root.appendChild(el)
        zip[relsPath] = serialize(doc)
        return id
    }

    fun removeRels(zip: MutableMap<String, ByteArray>, relsPath: String, predicate: (Rel) -> Boolean) {
        val bytes = zip[relsPath] ?: return
        val doc = parse(bytes)
        val root = doc.documentElement
        var changed = false
        for (el in root.kids()) {
            val rel = Rel(el.getAttribute("Id"), el.getAttribute("Type"), el.getAttribute("Target"))
            if (predicate(rel)) {
                root.removeChild(el); changed = true
            }
        }
        if (changed) zip[relsPath] = serialize(doc)
    }

    private fun contentTypes(zip: Map<String, ByteArray>): Document =
        zip["[Content_Types].xml"]?.let { parse(it) }
            ?: parse("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"$CT\"/>")

    fun addOverride(zip: MutableMap<String, ByteArray>, partName: String, contentType: String) {
        val doc = contentTypes(zip)
        val root = doc.documentElement
        val name = "/" + partName.trimStart('/')
        if (root.kids().any { it.localName == "Override" && it.getAttribute("PartName") == name }) return
        val el = doc.createElementNS(CT, "Override")
        el.setAttribute("PartName", name)
        el.setAttribute("ContentType", contentType)
        root.appendChild(el)
        zip["[Content_Types].xml"] = serialize(doc)
    }

    fun removeOverride(zip: MutableMap<String, ByteArray>, partName: String) {
        val doc = contentTypes(zip)
        val root = doc.documentElement
        val name = "/" + partName.trimStart('/')
        var changed = false
        for (el in root.kids()) if (el.localName == "Override" && el.getAttribute("PartName") == name) {
            root.removeChild(el); changed = true
        }
        if (changed) zip["[Content_Types].xml"] = serialize(doc)
    }

    fun ensureDefault(zip: MutableMap<String, ByteArray>, ext: String, contentType: String) {
        val doc = contentTypes(zip)
        val root = doc.documentElement
        if (root.kids().any { it.localName == "Default" && it.getAttribute("Extension").equals(ext, true) }) return
        val el = doc.createElementNS(CT, "Default")
        el.setAttribute("Extension", ext)
        el.setAttribute("ContentType", contentType)
        root.insertBefore(el, root.firstChild)
        zip["[Content_Types].xml"] = serialize(doc)
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') sb.append(' ') else sb.append(ch)
        }
        return sb.toString()
    }
}

/* ---------- DOM extension helpers ---------- */

fun Element.kids(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element) out.add(n)
        n = n.nextSibling
    }
    return out
}

fun Element.kid(local: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n is Element && n.localName == local) return n
        n = n.nextSibling
    }
    return null
}

fun Element.kidsNamed(local: String): List<Element> = kids().filter { it.localName == local }

/** All descendants (depth-first) with the given local name. */
fun Element.descendants(local: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(e: Element) {
        for (k in e.kids()) {
            if (k.localName == local) out.add(k)
            walk(k)
        }
    }
    walk(this)
    return out
}

/** Attribute in the given namespace, or null when missing/empty. */
fun Element.attrNS(ns: String, local: String): String? {
    val v = getAttributeNS(ns, local)
    return if (v.isNullOrEmpty()) null else v
}

fun Element.attr(name: String): String? {
    val v = getAttribute(name)
    return if (v.isNullOrEmpty()) null else v
}

fun Element.removeAllChildren() {
    while (firstChild != null) removeChild(firstChild)
}

/** Creates an element in the same namespace/prefix as the document root (useful for default-namespace parts). */
fun Document.elementLikeRoot(local: String): Element {
    val root = documentElement
    val prefix = root.prefix
    return createElementNS(root.namespaceURI, if (prefix.isNullOrEmpty()) local else "$prefix:$local")
}

/**
 * Inserts [child] into [parent] respecting a schema sequence [order] (list of local names).
 * Any existing child with the same local name is replaced.
 */
fun setOrdered(parent: Element, child: Element, order: List<String>) {
    for (k in parent.kids()) if (k.localName == child.localName) parent.removeChild(k)
    val idx = order.indexOf(child.localName)
    if (idx < 0) {
        parent.appendChild(child); return
    }
    val before = parent.kids().firstOrNull { val i = order.indexOf(it.localName); i > idx }
    if (before != null) parent.insertBefore(child, before) else parent.appendChild(child)
}

fun removeKids(parent: Element, vararg locals: String) {
    for (k in parent.kids()) if (k.localName in locals) parent.removeChild(k)
}
