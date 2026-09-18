package com.example.tindago.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.data.ProductCatalog
import com.example.tindago.ui.localization.Strings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySearchField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    lang: String,
    modifier: Modifier = Modifier
) {
    var searchMode by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val categoryName = if (selectedCategory.isBlank()) {
        "catAll".t(lang)
    } else {
        Strings.productCategoryLabel(selectedCategory, lang)
    }

    Box(modifier = modifier.fillMaxWidth().height(52.dp)) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searchMode) {
                // In search mode (web parity):
                // 1. The left button turns into the GridView/Exit search mode button
                // 2. The search input expands to fill the entire width
                Surface(
                    onClick = { searchMode = false; onSearchQueryChange("") },
                    modifier = Modifier.width(52.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                    color = Color.White
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.GridView, contentDescription = "Exit Search", tint = Gray700, modifier = Modifier.size(20.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.White, shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("searchItems".t(lang)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // In browse mode:
                // 1. Category selector takes up the left/middle weight (disappearing when searchMode is true)
                // 2. Search action button sits on the right
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Surface(
                        onClick = { dropdownExpanded = true },
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                        color = Color.White
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(categoryName, style = MaterialTheme.typography.bodyMedium, color = Gray800, maxLines = 1)
                            Text("▾", fontSize = 14.sp, color = Gray500)
                        }
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier
                            .width(280.dp)
                            .heightIn(max = 320.dp)
                            .background(Color.White, shape = RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("catAll".t(lang)) },
                            onClick = { onSelectCategory(""); dropdownExpanded = false }
                        )
                        ProductCatalog.CATEGORIES.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(Strings.productCategoryLabel(cat, lang)) },
                                onClick = { onSelectCategory(cat); dropdownExpanded = false }
                            )
                        }
                    }
                }
                Surface(
                    onClick = { searchMode = true },
                    modifier = Modifier.width(52.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                    color = Color.White
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Gray700, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

