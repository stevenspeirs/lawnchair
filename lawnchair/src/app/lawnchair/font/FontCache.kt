/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.lawnchair.font

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.Keep
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font as ComposeFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont as ComposeGoogleFont
import androidx.core.content.res.ResourcesCompat
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import app.lawnchair.font.googlefonts.GoogleFontsListing
import app.lawnchair.util.getDisplayName
import app.lawnchair.util.subscribeFiles
import app.lawnchair.util.uiHelperHandler
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import org.json.JSONArray
import org.json.JSONObject

@LauncherAppSingleton
class FontCache @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val scope = MainScope() + CoroutineName("FontCache")

    private val deferredFonts = mutableMapOf<Font, Deferred<LoadedFont?>>()

    private val cacheDir = context.cacheDir.apply { mkdirs() }
    private val customFontsDir = TTFFont.getFontsDir(context)
    val customFonts = customFontsDir.subscribeFiles()
        .map { files ->
            files.asSequence()
                .sortedByDescending { it.lastModified() }
                .map { TTFFont(context, it) }
                .filter { it.isAvailable }
                .map { Family(it) }
                .toList()
        }

    val uiRegular = ResourceFont(
        context,
        R.font.googlesansflex_variable,
        "Google Sans Flex Variable " + context.getString(R.string.font_weight_regular),
        mapOf(
            FontAxes.WEIGHT to FontWeight.Normal.weight.toFloat(),
            FontAxes.ROUNDNESS to 100f,
            FontAxes.GRADE to 0.0f,
        ),
    )

    val uiMedium = ResourceFont(
        context,
        R.font.googlesansflex_variable,
        "Google Sans Flex Variable " + context.getString(R.string.font_weight_medium),
        mapOf(
            FontAxes.WEIGHT to FontWeight.Medium.weight.toFloat(),
            FontAxes.ROUNDNESS to 100f,
            FontAxes.GRADE to 0f,
        ),
    )

    val uiText = ResourceFont(
        context,
        R.font.googlesansflex_variable,
        "Google Sans Flex Variable " + context.getString(R.string.font_weight_regular),
        mapOf(
            FontAxes.WEIGHT to FontWeight.Normal.weight.toFloat(),
            FontAxes.ROUNDNESS to 100f,
            FontAxes.GRADE to 0f,
        ),
    )

    val uiTextMedium = ResourceFont(
        context,
        R.font.googlesansflex_variable,
        "Google Sans Flex Variable " + context.getString(R.string.font_weight_medium),
        mapOf(
            FontAxes.WEIGHT to FontWeight.Medium.weight.toFloat(),
            FontAxes.ROUNDNESS to 100f,
            FontAxes.GRADE to 0.0f,
        ),
    )

    suspend fun getTypeface(font: Font): Typeface? {
        return loadFontAsync(font).await()?.typeface
    }

    fun preloadFont(font: Font) {
        @Suppress("DeferredResultUnused")
        loadFontAsync(font)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getLoadedFont(font: Font): LoadedFont? {
        val deferredFont = deferredFonts[font] ?: return null
        if (!deferredFont.isCompleted) return null
        return deferredFont.getCompleted()
    }

    private fun loadFontAsync(font: Font): Deferred<LoadedFont?> {
        return deferredFonts.getOrPut(font) {
            scope.async {
                font.load()?.let { LoadedFont(it) }
            }
        }
    }

    fun addCustomFont(uri: Uri) {
        val name = context.contentResolver.getDisplayName(uri) ?: throw AddFontException("Couldn't get file name")
        val file = TTFFont.getFile(context, name)
        val tmpFile = File(cacheDir.apply { mkdirs() }, file.name)

        if (file.exists()) return

        context.contentResolver.openInputStream(uri)?.use { input ->
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw AddFontException("Couldn't open file")

        if (Typeface.createFromFile(tmpFile) === Typeface.DEFAULT) {
            tmpFile.delete()
            throw AddFontException("Not a valid font file")
        }

        tmpFile.setLastModified(System.currentTimeMillis())
        tmpFile.renameTo(file)

        @Suppress("DeferredResultUnused")
        deferredFonts.remove(TTFFont(context, file))
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    class Family(val displayName: String, val variants: Map<String, Font>) {

        constructor(font: Font) : this(font.displayName, mapOf(Pair("regular", font)))

        val default = variants["regular"]
            ?: variants["400"]
            ?: variants.values.firstOrNull()
            ?: FontCache.SystemFont("sans-serif")

        val sortedVariants: List<Font> by lazy {
            variants.values.sortedWith(compareBy({ it.fontWeight }, { if (it.isItalic) 1 else 0 }))
        }
    }

    class TypefaceFamily(private val variants: Map<String, Typeface?>) {

        val default = variants["regular"]
            ?: variants["400"]
            ?: variants.values.firstOrNull()
            ?: FontCache.SystemFont("sans-serif")
    }

    sealed class Font {

        abstract val fullDisplayName: String
        abstract val displayName: String
        abstract val fontWeight: Int
        abstract val isItalic: Boolean
        open val isAvailable get() = true

        abstract val composeFontFamily: FontFamily

        abstract suspend fun load(): Typeface?

        open fun saveToJson(obj: JSONObject) {
            obj.put(KEY_CLASS_NAME, this::class.java.name)
        }

        open fun createWithWeight(weight: Int): Font {
            return this
        }

        fun toJsonString(): String {
            val obj = JSONObject()
            saveToJson(obj)
            return obj.toString()
        }

        companion object {

            fun fromJsonString(context: Context, jsonString: String): Font {
                val obj = JSONObject(jsonString)
                val className = obj.getString(KEY_CLASS_NAME)
                return Class.forName(className)
                    .getMethod("fromJson", Context::class.java, JSONObject::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, context, obj) as Font
            }
        }
    }

    sealed class TypefaceFont(protected val typeface: Typeface?) : Font() {

        override val fullDisplayName = typeface.toString()
        override val displayName get() = fullDisplayName
        override val fontWeight: Int
            get() = when (typeface?.style ?: Typeface.NORMAL) {
                Typeface.BOLD, Typeface.BOLD_ITALIC -> 700
                else -> 400
            }
        override val isItalic: Boolean
            get() = typeface?.style == Typeface.ITALIC || typeface?.style == Typeface.BOLD_ITALIC

        override val composeFontFamily: FontFamily
            get() = FontFamily(typeface ?: Typeface.DEFAULT)

        override suspend fun load(): Typeface? {
            return typeface
        }

        override fun equals(other: Any?): Boolean {
            return other is TypefaceFont && typeface == other.typeface
        }

        override fun hashCode(): Int {
            return fullDisplayName.hashCode()
        }
    }

    class DummyFont : TypefaceFont(null) {

        private val hashCode = "DummyFont".hashCode()

        override val fullDisplayName: String
            get() = "DummyFont"
        override val displayName: String
            get() = "Dummy"
        override val fontWeight: Int
            get() = 400
        override val isItalic: Boolean
            get() = false

        override val composeFontFamily = FontFamily(Typeface.DEFAULT)

        override fun equals(other: Any?): Boolean {
            return other is DummyFont
        }

        override fun hashCode(): Int {
            return hashCode
        }

        companion object {

            @Keep
            @JvmStatic
            fun fromJson(context: Context, obj: JSONObject): Font {
                return DummyFont()
            }
        }
    }

    class TTFFont(context: Context, private val file: File) : TypefaceFont(createTypeface(file)) {

        private val actualName: String = cleanFilename(file.name)
        override val isAvailable = typeface != null
        override val fullDisplayName: String = if (typeface == null) {
            context.getString(R.string.pref_fonts_missing_font)
        } else {
            actualName
        }
        override val displayName: String
            get() = fullDisplayName
        override val fontWeight: Int
            get() = parseFontWeight(actualName)
        override val isItalic: Boolean
            get() = parseItalic(actualName)

        override val composeFontFamily = FontFamily(typeface!!)

        fun delete() = file.delete()

        override fun saveToJson(obj: JSONObject) {
            super.saveToJson(obj)
            obj.put(KEY_FONT_NAME, file.name)
        }

        override fun equals(other: Any?): Boolean {
            return other is TTFFont && file.absolutePath == other.file.absolutePath
        }

        override fun hashCode(): Int = file.absolutePath.hashCode()

        companion object {

            private fun cleanFilename(fileName: String): String {
                val decoded = Uri.decode(fileName)

                val cleaned = decoded
                    .substringBeforeLast(".")
                    .replace(Regex("[_-]"), " ")
                    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { it.lowercase().replaceFirstChar { char -> char.uppercase() } }
                    .trim()

                return cleaned.ifBlank { decoded }
            }

            fun createTypeface(file: File): Typeface? = runCatching { Typeface.createFromFile(file) }.getOrNull()

            fun getFontsDir(context: Context): File {
                return File(context.filesDir, "customFonts").apply { mkdirs() }
            }

            fun getFile(context: Context, name: String): File {
                return File(getFontsDir(context), Uri.encode(name))
            }

            @Keep
            @JvmStatic
            fun fromJson(context: Context, obj: JSONObject): Font {
                val fontName = obj.getString(KEY_FONT_NAME)
                val decodedName = Uri.decode(fontName)
                return TTFFont(context, getFile(context, decodedName))
            }
        }
    }

    class SystemFont(
        val family: String,
        val style: Int = Typeface.NORMAL,
    ) : TypefaceFont(Typeface.create(family, style)) {

        private val hashCode = "SystemFont|$family|$style".hashCode()

        override val fullDisplayName = family
        override val displayName
            get() = fullDisplayName
        override val fontWeight: Int
            get() = when (style) {
                Typeface.BOLD, Typeface.BOLD_ITALIC -> 700
                else -> 400
            }
        override val isItalic: Boolean
            get() = style == Typeface.ITALIC || style == Typeface.BOLD_ITALIC
        override val composeFontFamily = FontFamily(typeface!!)

        override fun saveToJson(obj: JSONObject) {
            super.saveToJson(obj)
            obj.put(KEY_FAMILY_NAME, family)
            obj.put(KEY_STYLE, style)
        }

        override fun equals(other: Any?): Boolean {
            return other is SystemFont && family == other.family && style == other.style
        }

        override fun hashCode(): Int {
            return hashCode
        }

        override fun createWithWeight(weight: Int): Font {
            if (weight >= 700) {
                return SystemFont(family, Typeface.BOLD)
            }
            return super.createWithWeight(weight)
        }

        companion object {

            @Keep
            @JvmStatic
            fun fromJson(context: Context, obj: JSONObject): Font {
                val family = obj.getString(KEY_FAMILY_NAME)
                val style = obj.getInt(KEY_STYLE)
                return SystemFont(family, style)
            }
        }
    }

    class AssetFont(
        assets: AssetManager,
        private val name: String,
    ) : TypefaceFont(Typeface.createFromAsset(assets, "$name.ttf")) {

        private val hashCode = "AssetFont|$name".hashCode()

        override val fullDisplayName = name
        override val displayName: String
            get() = fullDisplayName
        override val fontWeight: Int
            get() = parseFontWeight(name)
        override val isItalic: Boolean
            get() = parseItalic(name)
        override val composeFontFamily = FontFamily(ComposeFont("$name.ttf", assets))

        override fun saveToJson(obj: JSONObject) {
            super.saveToJson(obj)
            obj.put(KEY_FAMILY_NAME, name)
        }

        override fun equals(other: Any?): Boolean {
            return other is AssetFont && name == other.name
        }

        override fun hashCode(): Int {
            return hashCode
        }

        companion object {

            @Keep
            @JvmStatic
            fun fromJson(context: Context, obj: JSONObject): Font {
                val assets = context.assets
                val name = obj.getString(KEY_FAMILY_NAME)
                return AssetFont(assets, name)
            }
        }
    }

    class ResourceFont(
        context: Context,
        private val resId: Int,
        private val name: String,
        private val axisSettings: Map<String, Float> = emptyMap(),
    ) : TypefaceFont(createTypeface(context, resId, axisSettings)) {

        private val hashCode = "ResourceFont|$name|$axisSettings".hashCode()

        override val fullDisplayName = name
        override val displayName: String
            get() = fullDisplayName
        override val fontWeight: Int
            get() = parseFontWeight(name)
        override val isItalic: Boolean
            get() = parseItalic(name)

        @OptIn(ExperimentalTextApi::class)
        override val composeFontFamily = FontFamily(
            // Don't let it fool you, removing qualifier name makes everything 10x worse
            androidx.compose.ui.text.font.Font(
                resId = resId,
                variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                    *axisSettings.map {
                        androidx.compose.ui.text.font.FontVariation.Setting(it.key, it.value)
                    }.toTypedArray(),
                ),
            ),
        )

        override fun saveToJson(obj: JSONObject) {
            super.saveToJson(obj)
            obj.put(KEY_RESOURCE_ID, resId)
            obj.put(KEY_FAMILY_NAME, name)
            val axesObj = JSONObject()
            axisSettings.forEach { (k, v) -> axesObj.put(k, v) }
            obj.put("axes", axesObj)
        }

        override fun equals(other: Any?): Boolean {
            return other is ResourceFont && name == other.name && axisSettings == other.axisSettings
        }

        override fun hashCode(): Int {
            return hashCode
        }

        companion object {
            private fun createTypeface(context: Context, resId: Int, axes: Map<String, Float>): Typeface? {
                if (axes.isEmpty()) {
                    return ResourcesCompat.getFont(context, resId)
                }
                return try {
                    context.resources.openRawResourceFd(resId).use { pfd ->
                        Typeface.Builder(pfd.fileDescriptor)
                            .setFontVariationSettings(FontAxes.mapToString(axes))
                            .build()
                    }
                } catch (e: Exception) {
                    ResourcesCompat.getFont(context, resId)
                }
            }

            @Keep
            @JvmStatic
            fun fromJson(context: Context, obj: JSONObject): Font {
                val resId = obj.getInt(KEY_RESOURCE_ID)
                val name = obj.getString(KEY_FAMILY_NAME)
                val axesMap = mutableMapOf<String, Float>()
                val axesObj = obj.optJSONObject("axes")
                axesObj?.keys()?.forEach { key ->
                    axesMap[key] = axesObj.getDouble(key).toFloat()
                }

                return ResourceFont(context, resId, name, axesMap)
            }
        }
    }

    class GoogleFont(
        private val context: Context,
        private val family: String,
        private val variant: String = "regular",
        private val variants: Array<String> = emptyArray(),
    ) : Font() {

        private val hashCode = "GoogleFont|$family|$variant".hashCode()

        override val displayName = createVariantName()
        override val fullDisplayName = "$family $displayName"
        override val fontWeight: Int
            get() = GoogleFontsListing.getWeight(variant).toInt()
        override val isItalic: Boolean
            get() = GoogleFontsListing.isItalic(variant)

        override val composeFontFamily = FontFamily(
            androidx.compose.ui.text.googlefonts.Font(
                googleFont = ComposeGoogleFont(family),
                fontProvider = provider,
                weight = FontWeight(GoogleFontsListing.getWeight(variant).toInt()),
                style = if (GoogleFontsListing.isItalic(variant)) FontStyle.Italic else FontStyle.Normal,
            ),
        )

        private fun createVariantName(): String {
            if (variant == "italic") return context.getString(R.string.font_variant_italic)
            val weight = GoogleFontsListing.getWeight(variant)
            val weightString = weightNameMap[weight]?.let(context::getString) ?: weight
            val italicString = if (GoogleFontsListing.isItalic(variant)) {
                " " + context.getString(R.string.font_variant_italic)
            } else {
                ""
            }
            return "$weightString$italicString"
        }

        override suspend fun load(): Typeface? {
            val request = FontRequest(
                // ProviderAuthority
                "com.google.android.gms.fonts",
                // ProviderPackage
                "com.google.android.gms",
                // Query
                GoogleFontsListing.buildQuery(family, variant),
                R.array.com_google_android_gms_fonts_certs,
            )

            return suspendCoroutine {
                FontsContractCompat.requestFont(
                    context,
                    request,
                    object : FontsContractCompat.FontRequestCallback() {
                        override fun onTypefaceRetrieved(typeface: Typeface) {
                            it.resume(typeface)
                        }

                        override fun onTypefaceRequestFailed(reason: Int) {
                            it.resume(null)
                        }
                    },
                    uiHelperHandler,
                )
            }
        }

        override fun saveToJson(obj: JSONObject) {
            super.saveToJson(obj)
            obj.put(KEY_FAMILY_NAME, family)
            obj.put(KEY_VARIANT, variant)
            val variantsArray = JSONArray()
            variants.forEach { variantsArray.put(it) }
            obj.put(KEY_VARIANTS, variantsArray)
        }

        override fun equals(other: Any?): Boolean {
            return other is GoogleFont && family == other.family && variant == other.variant
        }

        override fun hashCode(): Int {
            return hashCode
        }

        override fun createWithWeight(weight: Int): Font {
            if (weight == -1) return this
            val currentWeight = GoogleFontsListing.getWeight(variant).toInt()
            if (weight == currentWeight) return this
            val newVariant = if (weight > currentWeight) {
                findHeavier(weight, currentWeight, GoogleFontsListing.isItalic(variant))
            } else {
                findLighter(weight, currentWeight, GoogleFontsListing.isItalic(variant))
            }
            if (newVariant != null) {
                return GoogleFont(context, family, newVariant, variants)
            }
            return super.createWithWeight(weight)
        }

        private fun findHeavier(weight: Int, minWeight: Int, italic: Boolean): String? {
            val variants = variants.filter { it.contains("italic") == italic }
            return variants.lastOrNull {
                val variantWeight = GoogleFontsListing.getWeight(it).toInt()
                variantWeight in minWeight..weight
            } ?: variants.firstOrNull {
                GoogleFontsListing.getWeight(it).toInt() >= minWeight
            }
        }

        private fun findLighter(weight: Int, maxWeight: Int, italic: Boolean): String? {
            val variants = variants.filter { it.contains("italic") == italic }
            return variants.firstOrNull {
                val variantWeight = GoogleFontsListing.getWeight(it).toInt()
                variantWeight in weight..maxWeight
            } ?: variants.lastOrNull {
                GoogleFontsListing.getWeight(it).toInt() <= maxWeight
            }
        }

        companion object {

            @Keep
            @JvmStatic
            fun fromJson(context: Context, obj: JSONObject): Font {
                val family = obj.getString(KEY_FAMILY_NAME)
                val variant = obj.getString(KEY_VARIANT)
                val variantsArray = obj.optJSONArray(KEY_VARIANTS) ?: JSONArray()
                val variants = Array<String>(variantsArray.length()) { variantsArray.getString(it) }
                return GoogleFont(context, family, variant, variants)
            }
        }
    }

    class LoadedFont internal constructor(val typeface: Typeface)

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getFontCache)

        private const val KEY_CLASS_NAME = "className"
        private const val KEY_FAMILY_NAME = "family"
        private const val KEY_STYLE = "style"
        private const val KEY_VARIANT = "variant"
        private const val KEY_VARIANTS = "variants"
        private const val KEY_FONT_NAME = "font"
        private const val KEY_RESOURCE_ID = "resourceId"

        private val weightNameMap: Map<String, Int> = mapOf(
            Pair("100", R.string.font_weight_thin),
            Pair("200", R.string.font_weight_extra_light),
            Pair("300", R.string.font_weight_light),
            Pair("400", R.string.font_weight_regular),
            Pair("500", R.string.font_weight_medium),
            Pair("600", R.string.font_weight_semi_bold),
            Pair("700", R.string.font_weight_bold),
            Pair("800", R.string.font_weight_extra_bold),
            Pair("900", R.string.font_weight_extra_black),
        )

        val provider = ComposeGoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = R.array.com_google_android_gms_fonts_certs,
        )

        private val fontWeightRules: List<Pair<Int, List<Regex>>> = listOf(
            100 to listOf(
                Regex("""\b(hairline|thin)\b"""),
            ),
            200 to listOf(
                Regex("""\b(extra|ultra)[\s_-]?light\b"""),
            ),
            300 to listOf(
                Regex("""\b(light)\b"""),
            ),
            400 to listOf(
                Regex("""\b(normal|regular)\b"""),
            ),
            500 to listOf(
                Regex("""\b(medium)\b"""),
            ),
            600 to listOf(
                Regex("""\b(demi|semi)[\s_-]?bold\b"""),
            ),
            700 to listOf(
                Regex("""\b(bold)\b"""),
            ),
            800 to listOf(
                Regex("""\b(extra|ultra|x)[\s_-]?bold\b"""),
            ),
            900 to listOf(
                Regex("""\b((extra[\s_-]*)?black|heavy)\b"""),
            ),
        )

        private fun parseFontWeight(name: String): Int {
            val lower = name.lowercase()
            for ((value, patterns) in fontWeightRules) {
                if (patterns.any { it.containsMatchIn(lower) }) {
                    return value
                }
            }
            return 400
        }

        private fun parseItalic(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains(Regex("""\b(italic|oblique)\b"""))
        }
    }

    class AddFontException(message: String) : Exception(message)
}
