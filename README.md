# Office Suite for Android

An office app in the spirit of MobiOffice / WPS: **PDF editor**, **Word (.docx) editor** and **Excel (.xlsx) editor**
in one Android app. Kotlin + Jetpack Compose, minSdk 26 (Android 8.0+), targetSdk 35.

## Features

### PDF
- Smooth page viewer with pinch-zoom, page counter, go to page, search text, night mode
- **Edit existing text in place**: tap *Edit text*, every line of text is outlined; tap one, change it, and the original
  characters are removed from the PDF and the new text is written at the same position, size and color.
  The original embedded font is reused when it contains all the characters you typed; otherwise Helvetica or an
  embedded system font is used. The result is real, selectable, searchable PDF text.
  Lines that can't be rewritten (Arabic/Hebrew and other scripts that need shaping, text inside embedded graphics)
  are replaced visually instead (white cover + new text), and the app tells you when that happens.
- Add text boxes (any language, any size and color), move / resize / edit / delete them
- Signatures: draw, save for reuse, place, drag and resize
- Insert images, pen, highlighter, white-out, eraser
- Fill PDF forms (text fields, checkboxes, choice fields)
- Pages: rotate, reorder, delete, insert blank page, duplicate, extract to a new PDF
- Merge PDFs, images → PDF, add / remove password, PDF → Word, print, share
- Opens password-protected PDFs; undo / redo for everything, including in-place text edits

### Word (.docx, .txt)
- Rich text editing: bold, italic, underline, strikethrough, font size, text color, highlight,
  Title / Heading 1-3, alignment, bullet lists
- Shows existing images, tables, numbered and bulleted lists; insert images and tables; edit table cells
- Find & replace, word count, undo / redo
- Saves back to .docx **keeping everything it does not edit** (styles, page setup, headers/footers parts,
  equations, unknown content blocks, original fonts and spacing)
- Export to PDF, print, share, save as .txt

### Excel (.xlsx, .csv)
- Fast drawn grid with frozen headers, pinch-zoom, fling scrolling, long-press drag to select ranges
- Formula bar with tap-a-cell-to-insert-reference while typing formulas
- Formula engine with ~100 functions: SUM, AVERAGE, COUNT(A/IF/IFS), SUMIF(S), IF/IFS/IFERROR, VLOOKUP, HLOOKUP,
  XLOOKUP, INDEX, MATCH, text and date functions, ROUND family, PMT/FV/PV/NPV, STDEV, MEDIAN, … with cross-sheet
  references, absolute/relative references, circular-reference detection
- Formatting: bold/italic/underline, font size, text and fill colors, alignment, wrap, number formats
  (number, thousands, currency, percent, date, time, scientific, text)
- Insert / delete rows and columns (formulas are adjusted like Excel does), sort, copy / cut / paste
  (formulas shift), AutoSum, autofit column width, find, go to cell
- Multiple sheets: add, rename (formulas updated), delete
- Saves real .xlsx (keeps charts, merged cells, conditional formatting and other parts it doesn't edit),
  CSV export, PDF export, print, share

## Build it

### Option A: Android Studio (computer)
1. Install Android Studio (Ladybug 2024.2 or newer).
2. *File → Open* and choose this `OfficeSuite` folder. Let Gradle sync finish (it downloads the libraries).
3. Plug in your phone (USB debugging on) or start an emulator and press **Run ▶**.
4. For an installable file: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

### Option B: only a phone (free GitHub build)
1. Create a free account on github.com and a **new empty repository**.
2. In the repository: *Add file → Upload files* and upload **`OfficeSuite.zip`** (the zip itself).
3. *Add file → Create new file*, name it exactly `.github/workflows/build.yml` and paste:

```yaml
name: Build APK from zip
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: unzip -q OfficeSuite.zip -d src
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - name: Build
        working-directory: src/OfficeSuite
        run: chmod +x gradlew && ./gradlew :app:assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: OfficeSuite-apk
          path: src/OfficeSuite/app/build/outputs/apk/debug/*.apk
```

4. Commit. Open the **Actions** tab, wait for the green check (about 5–8 minutes), open the run and download
   **OfficeSuite-apk**. Unzip it on the phone and install `app-debug.apk` (allow "install unknown apps").

If you push the unzipped project instead, the included `.github/workflows/build-apk.yml` builds it the same way.

## Important notes
- This project was written without access to an Android SDK, so it has **not been compiled or run on a device yet**.
  The spreadsheet engine and the Office file reading/writing were compiled and tested separately (formulas, number
  formats, .xlsx round trip verified in LibreOffice and openpyxl; generated .docx verified in LibreOffice and
  python-docx), and the PDF in-place text editing technique was verified on real PDFs. The first Android build may
  still report small compile errors; send the error text and they can be fixed quickly.
- Not included yet: PowerPoint, old binary formats (.doc/.xls/.ppt), OCR for scanned PDFs, cloud drives, charts and
  pivot tables, track changes and comments editing, page-layout view (headers/footers) in the Word editor.
- Before publishing on Google Play: change `applicationId` in `app/build.gradle.kts`, create your own release
  signing key, replace the launcher icon and add a privacy policy.

## Project structure
```
app/src/main/java/com/officesuite/app/
  MainActivity.kt          navigation, opening files from other apps
  core/                    file access, recent files, Office package (zip/XML) helpers, templates
  home/                    home screen, PDF tools (merge, images→PDF, password, conversions)
  pdf/                     viewer, editor state, PDFBox engine, in-place text editor, dialogs
  word/                    .docx reader/writer, rich EditText, PDF export, editor screen
  sheet/                   model, formula engine, .xlsx/.csv I/O, grid, editor screen, PDF export
  ui/                      theme and shared dialogs
```

## Licenses
- PDF engine: [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache License 2.0)
- AndroidX / Jetpack Compose (Apache License 2.0)
