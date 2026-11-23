package com.officesuite.app.core

/** Minimal, valid Office packages used for "New document" / "New spreadsheet" and conversions. */
object Templates {
    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val REL_T = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /* ------------------------------ DOCX ------------------------------ */

    const val CT_DOC_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    const val CT_NUMBERING = "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"
    const val REL_NUMBERING = "$REL_T/numbering"
    const val REL_IMAGE = "$REL_T/image"

    private fun paragraphXml(text: String, style: String? = null): String {
        val pPr = if (style != null) "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" else ""
        if (text.isEmpty()) return "<w:p>$pPr</w:p>"
        return "<w:p>$pPr<w:r><w:t xml:space=\"preserve\">${OoXml.escape(text)}</w:t></w:r></w:p>"
    }

    fun newDocx(paragraphs: List<String> = listOf(""), title: String? = null): LinkedHashMap<String, ByteArray> {
        val body = StringBuilder()
        if (title != null) body.append(paragraphXml(title, "Title"))
        paragraphs.forEach { body.append(paragraphXml(it)) }
        if (paragraphs.isEmpty() && title == null) body.append("<w:p/>")

        val ns = "xmlns:w=\"${OoXml.W}\" xmlns:r=\"${OoXml.R}\" xmlns:wp=\"${OoXml.WP}\" xmlns:a=\"${OoXml.A}\" xmlns:pic=\"${OoXml.PIC}\""
        val document = XML_HEAD + "<w:document $ns><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"

        val heading = { id: String, name: String, sz: Int, lvl: Int ->
            "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/>" +
                "<w:outlineLvl w:val=\"$lvl\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr></w:style>"
        }
        val border = { side: String -> "<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" }
        val styles = XML_HEAD + "<w:styles xmlns:w=\"${OoXml.W}\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Arial\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
            "<w:qFormat/><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:rPr><w:sz w:val=\"56\"/><w:szCs w:val=\"56\"/></w:rPr></w:style>" +
            heading("Heading1", "heading 1", 36, 0) + heading("Heading2", "heading 2", 30, 1) + heading("Heading3", "heading 3", 26, 2) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:qFormat/><w:pPr><w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblBorders>" +
            border("top") + border("left") + border("bottom") + border("right") + border("insideH") + border("insideV") +
            "</w:tblBorders></w:tblPr></w:style></w:styles>"

        val lvl = { i: Int, sym: String ->
            "<w:lvl w:ilvl=\"$i\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"$sym\"/><w:lvlJc w:val=\"left\"/>" +
                "<w:pPr><w:ind w:left=\"${720 * (i + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        }
        val numbering = XML_HEAD + "<w:numbering xmlns:w=\"${OoXml.W}\"><w:abstractNum w:abstractNumId=\"0\">" +
            "<w:multiLevelType w:val=\"hybridMultilevel\"/>" + lvl(0, "\u2022") + lvl(1, "\u25E6") + lvl(2, "\u25AA") +
            "</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"

        val contentTypes = XML_HEAD + "<Types xmlns=\"${OoXml.CT}\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_DOC_MAIN\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"$CT_NUMBERING\"/>" +
            "</Types>"
        val rootRels = XML_HEAD + "<Relationships xmlns=\"${OoXml.PKG_REL}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoXml.REL_OFFICE_DOC}\" Target=\"word/document.xml\"/></Relationships>"
        val docRels = XML_HEAD + "<Relationships xmlns=\"${OoXml.PKG_REL}\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_T/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$REL_NUMBERING\" Target=\"numbering.xml\"/></Relationships>"

        return linkedMapOf(
            "[Content_Types].xml" to contentTypes.toByteArray(),
            "_rels/.rels" to rootRels.toByteArray(),
            "word/document.xml" to document.toByteArray(),
            "word/styles.xml" to styles.toByteArray(),
            "word/numbering.xml" to numbering.toByteArray(),
            "word/_rels/document.xml.rels" to docRels.toByteArray(),
        )
    }

    /* ------------------------------ XLSX ------------------------------ */

    const val CT_SHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    const val REL_SHEET = "$REL_T/worksheet"

    fun newSheetXml(): String = XML_HEAD + "<worksheet xmlns=\"${OoXml.S}\" xmlns:r=\"${OoXml.R}\"><sheetData/></worksheet>"

    fun newXlsx(sheetNames: List<String> = listOf("Sheet1")): LinkedHashMap<String, ByteArray> {
        val sheets = StringBuilder()
        val rels = StringBuilder()
        val overrides = StringBuilder()
        val files = LinkedHashMap<String, ByteArray>()
        sheetNames.forEachIndexed { i, name ->
            val n = i + 1
            sheets.append("<sheet name=\"${OoXml.escape(name)}\" sheetId=\"$n\" r:id=\"rId$n\"/>")
            rels.append("<Relationship Id=\"rId$n\" Type=\"$REL_SHEET\" Target=\"worksheets/sheet$n.xml\"/>")
            overrides.append("<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"$CT_SHEET\"/>")
            files["xl/worksheets/sheet$n.xml"] = newSheetXml().toByteArray()
        }
        val stylesRel = sheetNames.size + 1
        val workbook = XML_HEAD + "<workbook xmlns=\"${OoXml.S}\" xmlns:r=\"${OoXml.R}\">" +
            "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>$sheets</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"
        val wbRels = XML_HEAD + "<Relationships xmlns=\"${OoXml.PKG_REL}\">$rels" +
            "<Relationship Id=\"rId$stylesRel\" Type=\"$REL_T/styles\" Target=\"styles.xml\"/></Relationships>"
        val styles = XML_HEAD + "<styleSheet xmlns=\"${OoXml.S}\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/><scheme val=\"minor\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
        val contentTypes = XML_HEAD + "<Types xmlns=\"${OoXml.CT}\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            overrides + "</Types>"
        val rootRels = XML_HEAD + "<Relationships xmlns=\"${OoXml.PKG_REL}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoXml.REL_OFFICE_DOC}\" Target=\"xl/workbook.xml\"/></Relationships>"

        val out = linkedMapOf(
            "[Content_Types].xml" to contentTypes.toByteArray(),
            "_rels/.rels" to rootRels.toByteArray(),
            "xl/workbook.xml" to workbook.toByteArray(),
            "xl/_rels/workbook.xml.rels" to wbRels.toByteArray(),
            "xl/styles.xml" to styles.toByteArray(),
        )
        out.putAll(files)
        return out
    }
}
