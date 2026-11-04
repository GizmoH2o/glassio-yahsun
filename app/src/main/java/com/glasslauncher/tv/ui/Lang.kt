package com.glasslauncher.tv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import java.io.File
import java.util.Locale

/**
 * Glassio's language (Settings › Look & motion): English, Portuguese, Spanish, German or Russian, the ones Live TV has.
 * The copy stays in the code in English; each language is a table of it in assets/i18n, read when it's chosen. In a file,
 * not the preferences: Live TV's process reads it too. A title's or channel's own name is drawn with [RawText], never looked up.
 */
object Lang {
    val CHOICES = listOf("auto" to "Auto", "en" to "English", "pt" to "Português", "es" to "Español", "de" to "Deutsch", "ru" to "Русский")
    private val HAVE = setOf("pt", "es", "de", "ru")

    /** What's on ("en", "es"…): Compose reads it, so a change redraws every text. */
    val code = mutableStateOf("en")
    /** What Settings shows: "auto" or a language. */
    val choice = mutableStateOf("auto")
    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Regex, String>> = emptyList()
    private val found = HashMap<String, String>()

    private fun file(ctx: Context) = File(ctx.filesDir, "ui_language")

    private fun chosen(ctx: Context): String = runCatching { file(ctx).readText().trim() }.getOrNull()?.ifBlank { null } ?: "auto"

    /** As Glassio starts. */
    fun load(ctx: Context) = apply(ctx, chosen(ctx))

    fun choose(ctx: Context, choice: String) {
        runCatching { file(ctx).writeText(choice) }
        apply(ctx, choice)
        com.glasslauncher.tv.live.LiveHome.languageChanged()
    }

    private fun apply(ctx: Context, choice: String) {
        this.choice.value = choice
        val lang = (if (choice == "auto") Locale.getDefault().language else choice).takeIf { it in HAVE } ?: "en"
        val table = if (lang == "en") emptyList() else runCatching {
            ctx.assets.open("i18n/$lang.txt").bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())
        set(lang, table)
    }

    /** From a table's lines, "English<tab>translation". [ ] %s and %d in the English stand for what's filled in. Pure but for the state. */
    internal fun set(lang: String, table: List<String>) {
        val plain = HashMap<String, String>()
        val pat = ArrayList<Pair<Regex, String>>()
        for (line in table) {
            val at = line.indexOf('\t')
            if (at <= 0) continue
            val en = line.substring(0, at)
            val tr = line.substring(at + 1)
            if (en.contains("%s") || en.contains("%d")) pat += patternOf(en) to tr else plain[en] = tr
        }
        synchronized(found) { found.clear(); exact = plain; patterns = pat }
        code.value = lang
    }

    private val SLOT = Regex("%[sd]")

    private fun patternOf(en: String): Regex {
        val out = StringBuilder("^")
        var last = 0
        for (m in SLOT.findAll(en)) {
            out.append(Regex.escape(en.substring(last, m.range.first)))
            out.append(if (m.value == "%d") "(\\d+)" else "(.+)")
            last = m.range.last + 1
        }
        return Regex(out.append(Regex.escape(en.substring(last))).append('$').toString())
    }

    /** [english] in the language on; as it is when the table doesn't have it. */
    fun tr(english: String): String {
        if (exact.isEmpty() && patterns.isEmpty()) return english
        synchronized(found) {
            found[english]?.let { return it }
            val done = exact[english] ?: fill(english) ?: english
            if (found.size > 4000) found.clear()
            found[english] = done
            return done
        }
    }

    private fun fill(english: String): String? {
        for ((re, to) in patterns) {
            val m = re.find(english) ?: continue
            val values = m.groupValues.drop(1).iterator()
            return SLOT.replace(to) { if (values.hasNext()) values.next() else "" }
        }
        return null
    }

    /** A weather word: its own table entries, as "Clear" alone means something else. */
    fun weather(english: String): String = tr("weather:$english").removePrefix("weather:")

    /** For dates and day names. */
    fun locale(): Locale = when (code.value) {
        "pt" -> Locale("pt", "BR")
        "es" -> Locale("es", "ES")
        "de" -> Locale.GERMANY
        "ru" -> Locale("ru", "RU")
        else -> Locale.ENGLISH
    }
}

/** Text in the language on. Every screen of Glassio's own draws its words with this. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    Lang.code.value
    MaterialText(
        Lang.tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style,
    )
}

/** A title's, channel's or person's own name: drawn as it is. */
@Composable
fun RawText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
) {
    MaterialText(text, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, letterSpacing = letterSpacing, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines)
}
