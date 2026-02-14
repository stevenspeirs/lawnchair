package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.font.FontAxes
import app.lawnchair.font.FontCache
import app.lawnchair.font.googlefonts.GoogleFontsListing
import app.lawnchair.preferences.BasePreferenceManager
import app.lawnchair.preferences.PreferenceAdapter
import app.lawnchair.preferences.getAdapter
import app.lawnchair.ui.OverflowMenu
import app.lawnchair.ui.preferences.components.layout.PreferenceDivider
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupItem
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceSearchScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import com.android.launcher3.R

private enum class ContentType {
    ADD_BUTTON,
    FONT,
}

@Composable
fun FontSelection(
    fontPref: BasePreferenceManager.FontPref,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val customFonts by remember { FontCache.INSTANCE.get(context).customFonts }.collectAsStateWithLifecycle(initialValue = emptyList())
    val items by produceState(initialValue = emptyList<FontCache.Family>()) {
        fun flex(weight: Int, label: Int) = FontCache.ResourceFont(
            context,
            R.font.googlesansflex_variable,
            "Google Sans Flex Variable " + context.getString(label),
            mapOf(FontAxes.WEIGHT to weight.toFloat()),
        )
        val list = mutableListOf<FontCache.Family>()
        list.add(FontCache.Family(FontCache.SystemFont("sans-serif")))
        list.add(FontCache.Family(FontCache.SystemFont("sans-serif-condensed")))
        list.add(FontCache.Family(FontCache.SystemFont("sans-serif-medium")))
        val flexVariants = mapOf(
            "100" to flex(100, R.string.font_weight_thin),
            "200" to flex(200, R.string.font_weight_extra_light),
            "300" to flex(300, R.string.font_weight_light),
            "400" to flex(400, R.string.font_weight_regular),
            "500" to flex(500, R.string.font_weight_medium),
            "600" to flex(600, R.string.font_weight_semi_bold),
            "700" to flex(700, R.string.font_weight_bold),
            "800" to flex(800, R.string.font_weight_extra_bold),
            "900" to flex(900, R.string.font_weight_extra_black),
        )
        list.add(
            FontCache.Family(
                displayName = "Google Sans Flex Variable",
                variants = flexVariants,
            ),
        )
        GoogleFontsListing.INSTANCE
            .get(context)
            .getFonts()
            .sortedBy { it.family.lowercase() }
            .mapTo(list) { font ->
                val variantsMap = HashMap<String, FontCache.Font>()
                val variants = font.variants
                    .sortedBy { GoogleFontsListing.getWeight(it).toInt() }
                    .toTypedArray()
                font.variants.forEach { variant ->
                    variantsMap[variant] = FontCache.GoogleFont(context, font.family, variant, variants)
                }
                FontCache.Family(font.family, variantsMap)
            }
        value = list
    }
    val allItems by remember { derivedStateOf { items + customFonts } }
    val adapter = fontPref.getAdapter()

    LaunchedEffect(items, customFonts) {
        val currentFont = adapter.state.value ?: return@LaunchedEffect

        val allFonts = items.flatMap { it.variants.values } +
            customFonts.flatMap { it.variants.values }

        val matchedFont = allFonts.firstOrNull { candidate ->
            candidate::class == currentFont::class &&
                candidate.displayName == currentFont.displayName &&
                candidate.fontWeight == currentFont.fontWeight &&
                candidate.isItalic == currentFont.isItalic
        }

        if (currentFont !is FontCache.TTFFont &&
            matchedFont != null &&
            matchedFont != currentFont
        ) {
            adapter.onChange(matchedFont)
        }
    }

    var searchQuery by remember { mutableStateOf("") }

    val hasFilter by remember { derivedStateOf { searchQuery.isNotEmpty() } }
    val filteredItems by remember {
        derivedStateOf {
            if (hasFilter) {
                val lowerCaseQuery = searchQuery.lowercase()
                allItems.filter { it.displayName.lowercase().contains(lowerCaseQuery) }
            } else {
                allItems
            }
        }
    }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        try {
            FontCache.INSTANCE.get(context).addCustomFont(uri)
        } catch (e: FontCache.AddFontException) {
            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    PreferenceSearchScaffold(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        modifier = modifier,
        placeholder = {
            Text(
                text = stringResource(id = R.string.label_search),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            OverflowMenu {
                DropdownMenuItem(
                    onClick = {
                        fontPref.set(fontPref.defaultValue)
                        hideMenu()
                    },
                    text = {
                        Text(text = stringResource(id = R.string.action_reset))
                    },
                )
            }
        },
    ) { padding ->
        PreferenceLazyColumn(padding) {
            if (!hasFilter) {
                item(contentType = { ContentType.ADD_BUTTON }) {
                    PreferenceGroupItem(
                        modifier = Modifier.padding(top = 8.dp),
                        cutBottom = customFonts.isNotEmpty(),
                    ) {
                        PreferenceTemplate(
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_GET_CONTENT)
                                intent.addCategory(Intent.CATEGORY_OPENABLE)
                                intent.type = "*/*"
                                request.launch(intent)
                            },
                            title = { Text(stringResource(id = R.string.pref_fonts_add_fonts)) },
                            description = { Text(stringResource(id = R.string.pref_fonts_add_fonts_summary)) },
                            startWidget = {
                                Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                            },
                        )
                    }
                }
                itemsIndexed(
                    items = customFonts,
                    key = { _, family -> family.displayName },
                    contentType = { _, _ -> ContentType.FONT },
                ) { index, family ->
                    PreferenceGroupItem(
                        cutTop = true,
                        cutBottom = index != customFonts.lastIndex,
                    ) {
                        PreferenceDivider(startIndent = 40.dp)
                        FontSelectionItem(
                            adapter = adapter,
                            family = family,
                            onDelete = {
                                val selected = family.variants.any { it.value == adapter.state.value }
                                if (selected) {
                                    fontPref.set(fontPref.defaultValue)
                                }
                                (family.default as? FontCache.TTFFont)?.delete()
                            },
                        )
                    }
                }
            }
            preferenceGroupItems(
                filteredItems,
                isFirstChild = false,
                key = { _, family -> family.displayName },
                contentType = { ContentType.FONT },
            ) { _, family ->
                FontSelectionItem(
                    adapter = adapter,
                    family = family,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FontSelectionItem(
    adapter: PreferenceAdapter<FontCache.Font>,
    family: FontCache.Family,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val selected = family.variants.any { it.value == adapter.state.value }
    PreferenceTemplate(
        modifier = modifier
            .clickable {
                adapter.onChange(
                    adapter.state.value.takeIf { it in family.variants.values }
                        ?: family.default,
                )
            },
        title = {
            Box(modifier = Modifier.height(52.dp)) {
                Text(
                    text = family.displayName,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(),
                    fontFamily = family.default.composeFontFamily,
                )
            }
        },
        startWidget = {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier
                    .padding(horizontal = 16.dp),
            )
        },
        endWidget = when {
            selected && family.variants.size > 1 -> {
                {
                    VariantDropdown(adapter = adapter, family = family)
                }
            }

            onDelete != null -> {
                {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.padding(end = 8.dp),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(id = R.string.action_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> null
        },
        applyPaddings = false,
        verticalPadding = 0.dp,
    )
}

private val VariantButtonContentPadding = PaddingValues(
    start = 8.dp,
    top = 8.dp,
    end = 0.dp,
    bottom = 8.dp,
)

private fun removeFamilyPrefix(
    familyName: CharSequence,
    fontName: CharSequence,
): String {
    return fontName.removePrefix(familyName).trim().toString()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VariantDropdown(
    adapter: PreferenceAdapter<FontCache.Font>,
    family: FontCache.Family,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .wrapContentWidth()
            .padding(end = 16.dp),
    ) {
        val selectedFont = adapter.state.value
        var showVariants by remember { mutableStateOf(false) }

        val context = LocalContext.current
        LaunchedEffect(family) {
            val fontCache = FontCache.INSTANCE.get(context)
            family.variants.forEach { fontCache.preloadFont(it.value) }
        }

        TextButton(
            onClick = { showVariants = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = VariantButtonContentPadding,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(
                text = removeFamilyPrefix(family.displayName, selectedFont.displayName),
                fontFamily = selectedFont.composeFontFamily,
                modifier = Modifier.wrapContentWidth(),
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = showVariants,
            onDismissRequest = { showVariants = false },
        ) {
            family.sortedVariants.forEach { font ->
                DropdownMenuItem(
                    onClick = {
                        adapter.onChange(font)
                        showVariants = false
                    },
                    text = {
                        Text(
                            text = removeFamilyPrefix(family.displayName, font.displayName),
                            fontFamily = font.composeFontFamily,
                        )
                    },
                )
            }
        }
    }
}
