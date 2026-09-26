package com.example.tindago.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.data.ProductCatalog
import com.example.tindago.ui.localization.Strings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import kotlinx.coroutines.delay

// Inventory two-level drill-down (index.html Section 2A+2B+2C parity).
// Category -> subcategory; mirrors CheckoutCategorySearchField's field behavior.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySearchField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: String,
    selectedSubcategory: String,
    onSelectCategory: (String) -> Unit,
    onSelectSubcategory: (String) -> Unit,
    onClearCategoryFilter: () -> Unit,
    lang: String,
    modifier: Modifier = Modifier,
    searchMode: Boolean,
    onSearchModeChange: (Boolean) -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var dropdownLevel by remember { mutableStateOf("categories") }
    val focusRequester = remember { FocusRequester() }
    val labelText = when {
        selectedSubcategory.isNotBlank() -> Strings.productSubcategoryLabel(selectedSubcategory, lang)
        selectedCategory.isNotBlank() -> Strings.productCategoryLabel(selectedCategory, lang)
        // inventory.html updLabel(): default button label is t('catAll') -> "All".
        else -> "catAll".t(lang)
    }

    // inventory.html parity: 1px #cfcfcf outline, 52px height, 12px radius.
    val webBorder = Color(0xFFCFCFCF)
    val webText = Color(0xFF222222)
    val webMuted = Color(0xFF777777)
    val webSearchIcon = Color(0xFF555555)

    // index.html Section 2C: focus after 50ms when entering search mode
    LaunchedEffect(searchMode) {
        if (searchMode) {
            delay(50)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
    LaunchedEffect(selectedCategory, selectedSubcategory) {
        if (selectedCategory.isBlank()) dropdownLevel = "categories"
        else if (selectedSubcategory.isNotBlank()) dropdownLevel = "subcategories"
    }

    // inventory.html parity: default = category box LEFT (flex:1, rounded
    // outer-left) + 52px search square RIGHT (rounded outer-right) sharing one
    // 1px outline. Search-mode keeps the same visual order: tiles square LEFT
    // (square outer-left, rounded inner-right) + plain input RIGHT (rounded
    // inner-left, square outer-right); inner corners are rounded.
    val outerShape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (searchMode) Modifier else Modifier.border(1.dp, webBorder, outerShape))
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searchMode) {
                // Category tiles — square on the outer (left) edge, rounded on the
                // inner (right) edge (inventory.html search-mode radii).
                Surface(
                    onClick = { onSearchModeChange(false); onSearchQueryChange("") },
                    modifier = Modifier.width(52.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, webBorder)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        // Web .tiles-icon: 2x2 grid of 7px squares, 1.8px #444, 3px gap.
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(2) { Box(Modifier.size(7.dp).border(1.8.dp, Color(0xFF444444), RoundedCornerShape(1.5.dp))) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(2) { Box(Modifier.size(7.dp).border(1.8.dp, Color(0xFF444444), RoundedCornerShape(1.5.dp))) }
                            }
                        }
                    }
                }
                // Plain flat search input (web <input class="search-input"> parity):
                // flat/16sp text, rounded on the inner (left) edge.
                val searchInputShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 0.dp, bottomEnd = 0.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.White, shape = searchInputShape)
                        .border(1.dp, webBorder, searchInputShape)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = webText),
                        cursorBrush = SolidColor(Green600),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text("searchPlaceholder".t(lang), fontSize = 16.sp, color = Gray400)
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Surface(
                        onClick = {
                            if (dropdownExpanded) dropdownExpanded = false
                            else if (selectedCategory.isNotBlank()) { dropdownLevel = "subcategories"; dropdownExpanded = true }
                            else { dropdownLevel = "categories"; dropdownExpanded = true }
                        },
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                        color = Color.White
                    ) {
                        // Web .category-button: 16px #222, padding 0 16px, label left + arrow right.
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                labelText,
                                fontSize = 16.sp,
                                color = webText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text("\u25BE", fontSize = 12.sp, color = webMuted, modifier = Modifier.padding(start = 10.dp))
                        }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        offset = DpOffset(0.dp, 6.dp),
                        modifier = Modifier
                            .width(280.dp)
                            .heightIn(max = 320.dp)
                            .background(Color.White, shape = RoundedCornerShape(12.dp))
                    ) {
                        if (dropdownLevel == "categories") {
                            DropdownMenuItem(
                                text = { Text("catAll".t(lang)) },
                                onClick = { onClearCategoryFilter(); dropdownLevel = "categories"; dropdownExpanded = false }
                            )
                            ProductCatalog.CATEGORIES.forEach { cat ->
                                val isSelected = cat == selectedCategory && selectedSubcategory.isBlank()
                                DropdownMenuItem(
                                    text = { Text(Strings.productCategoryLabel(cat, lang), fontWeight = if (isSelected) FontWeight.SemiBold else null) },
                                    onClick = { onSelectCategory(cat); dropdownLevel = "subcategories" }
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { dropdownLevel = "categories" }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back to categories", tint = Gray700, modifier = Modifier.size(18.dp).clickable { dropdownLevel = "categories" })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(Strings.productCategoryLabel(selectedCategory, lang) + " \u2014 " + "subcategoriesLabel".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500, maxLines = 1)
                            }
                            HorizontalDivider(color = webBorder)
                            val subs = ProductCatalog.SUBCATEGORIES[selectedCategory] ?: emptyList()
                            subs.forEach { sub ->
                                val isSelected = sub == selectedSubcategory
                                DropdownMenuItem(
                                    text = { Text(Strings.productSubcategoryLabel(sub, lang), fontWeight = if (isSelected) FontWeight.SemiBold else null) },
                                    onClick = { onSelectSubcategory(sub); dropdownLevel = "subcategories"; dropdownExpanded = false }
                                )
                            }
                        }
                    }
                }
                Surface(
                    onClick = { onSearchModeChange(true); dropdownExpanded = false },
                    modifier = Modifier.width(52.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp),
                    color = Color.White
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = webSearchIcon, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
}

