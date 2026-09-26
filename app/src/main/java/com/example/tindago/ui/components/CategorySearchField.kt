package com.example.tindago.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.data.ProductCatalog
import com.example.tindago.ui.localization.Strings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import kotlinx.coroutines.delay

/** [search-control] morphing drill-down field shared by Checkout and Stocks.
 *  Collapsed = category button + search icon; expands into a text search input
 *  (index.html Sections 2A+2B+2C parity). Callers supply their own filter state.
 *
 *  [categoryPlaceholder] is the collapsed default label:
 *   - Checkout passes the default "Category" (web index.html collapsed label).
 *   - Stocks passes "catAll".t(lang) -> "All"/"Lahat" (inventory.html updLabel t('catAll')). */
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
    categoryPlaceholder: String = "Category"
) {
    var searchMode by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var dropdownLevel by remember { mutableStateOf("categories") }
    val focusRequester = remember { FocusRequester() }
    val labelText = when {
        selectedSubcategory.isNotBlank() -> Strings.productSubcategoryLabel(selectedSubcategory, lang)
        selectedCategory.isNotBlank() -> Strings.productCategoryLabel(selectedCategory, lang)
        else -> categoryPlaceholder
    }
    LaunchedEffect(searchMode) {
        if (searchMode) { delay(50); try { focusRequester.requestFocus() } catch (_: Exception) {} }
    }
    LaunchedEffect(selectedCategory, selectedSubcategory) {
        if (selectedCategory.isBlank()) dropdownLevel = "categories"
        else if (selectedSubcategory.isNotBlank()) dropdownLevel = "subcategories"
    }
    Box(modifier = modifier.fillMaxWidth().height(52.dp)) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (searchMode) {
                Surface(onClick = { searchMode = false; onSearchQueryChange("") }, modifier = Modifier.width(52.dp).fillMaxHeight(), shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp), color = Color.White) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Icon(Icons.Default.GridView, contentDescription = "Exit Search", tint = Gray700, modifier = Modifier.size(20.dp)) }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.White, shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    OutlinedTextField(value = searchQuery, onValueChange = onSearchQueryChange, placeholder = { Text("Search...", color = Gray500) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent), modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Surface(onClick = { if (dropdownExpanded) dropdownExpanded = false else if (selectedCategory.isNotBlank()) { dropdownLevel = "subcategories"; dropdownExpanded = true } else { dropdownLevel = "categories"; dropdownExpanded = true } }, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp), color = Color.White) {
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(labelText, style = MaterialTheme.typography.bodyMedium, color = Gray800, maxLines = 1)
                            Text("\u25BE", fontSize = 14.sp, color = Gray500)
                        }
                    }
                    DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }, modifier = Modifier.width(280.dp).heightIn(max = 360.dp).background(Color.White, shape = RoundedCornerShape(12.dp))) {
                        if (dropdownLevel == "categories") {
                            DropdownMenuItem(text = { Text("catAll".t(lang)) }, onClick = { onClearCategoryFilter(); dropdownLevel = "categories"; dropdownExpanded = false })
                            ProductCatalog.CATEGORIES.forEach { cat ->
                                val isSelected = cat == selectedCategory && selectedSubcategory.isBlank()
                                DropdownMenuItem(text = { Text(Strings.productCategoryLabel(cat, lang), fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else null) }, onClick = { onSelectCategory(cat); dropdownLevel = "subcategories" })
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth().clickable { dropdownLevel = "categories" }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back to categories", tint = Gray700, modifier = Modifier.size(18.dp).clickable { dropdownLevel = "categories" })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(Strings.productCategoryLabel(selectedCategory, lang) + " \u2014 " + "subcategoriesLabel".t(lang), style = MaterialTheme.typography.labelMedium, color = Gray500, maxLines = 1)
                            }
                            HorizontalDivider(color = Gray200)
                            val subs = ProductCatalog.SUBCATEGORIES[selectedCategory] ?: emptyList()
                            subs.forEach { sub ->
                                val isSelected = sub == selectedSubcategory
                                DropdownMenuItem(text = { Text(Strings.productSubcategoryLabel(sub, lang), fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else null) }, onClick = { onSelectSubcategory(sub); dropdownLevel = "subcategories"; dropdownExpanded = false })
                            }
                        }
                    }
                }
                Surface(onClick = { searchMode = true; dropdownExpanded = false }, modifier = Modifier.width(52.dp).fillMaxHeight(), shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp), color = Color.White) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Icon(Icons.Default.Search, contentDescription = "Search", tint = Gray700, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

