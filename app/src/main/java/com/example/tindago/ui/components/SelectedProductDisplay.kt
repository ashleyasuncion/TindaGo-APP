package com.example.tindago.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tindago.data.Product
import com.example.tindago.ui.localization.Strings
import com.example.tindago.ui.localization.t
import com.example.tindago.ui.theme.*
import com.example.tindago.ui.util.performHapticFeedback

/**
 * Selected Product Display — compact card showing the currently selected product
 * above the quantity selector (web v2.63/v2.64 parity).
 *
 * Features:
 * - Product name, category → subcategory, brand, unit, price (₱)
 * - Stock badge: green (OK >5), amber (low ≤5), red (out of stock)
 * - Clear button (×) to deselect product and return to picker
 * - Accessibility: live region, screen-reader labels
 * - i18n: all labels translatable (EN/Filipino)
 * - Haptics: selection click on clear
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedProductDisplay(
    product: Product?,
    onClear: () -> Unit,
    lang: String,
    modifier: Modifier = Modifier
) {
    val hapticView = LocalView.current
    if (product == null) return

    val brand = product.brand?.let { " $it" } ?: ""
    val unit = product.unit?.let { " / $it" } ?: ""
    val catLabel = product.category?.let { Strings.productCategoryLabel(it, lang) } ?: ""
    val subLabel = product.subcategory?.let { Strings.productSubcategoryLabel(it, lang) } ?: ""
    val metaParts = listOf(catLabel, subLabel).filter { it.isNotBlank() }.joinToString(" — ") + brand + unit

    val stock = product.quantity
    val (stockText, stockColor) = when {
        stock <= 0 -> "outOfStock".t(lang) to Red600
        stock <= 5 -> "lowStock".t(lang) to Amber700
        else -> "inStock".t(lang) to Green600
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "selectedProduct".t(lang)
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Green50,
            contentColor = Gray800
        ),
        border = BorderStroke(1.dp, Green200)
    ) {
        Row(
            modifier = Modifier.padding(10.dp, 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                // Product name
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Meta: category — subcategory + brand + unit
                if (metaParts.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        metaParts,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray500,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Price
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "₱${String.format("%,.2f", product.sellingPrice)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Green600
                )

                // Stock badge
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "$stockText ($stock)",
                    style = MaterialTheme.typography.bodySmall,
                    color = stockColor,
                    fontWeight = FontWeight.Medium
                )
            }

            // Clear button
            IconButton(
                onClick = {
                    performHapticFeedback(hapticView)
                    onClear()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "clear".t(lang),
                    tint = Gray500,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "SelectedProductDisplay - In Stock")
@Composable
fun SelectedProductDisplayPreviewInStock() {
    TindaGoTheme {
        SelectedProductDisplay(
            product = Product(
                id = 1,
                name = "Coca Cola 1.5L",
                quantity = 20,
                costPrice = 45.0,
                sellingPrice = 65.0,
                unit = "bottle",
                brand = "Coca Cola",
                packageSize = "1.5L",
                category = "beverages",
                subcategory = "soft_drinks"
            ),
            onClear = {},
            lang = "en"
        )
    }
}

@Preview(showBackground = true, name = "SelectedProductDisplay - Low Stock")
@Composable
fun SelectedProductDisplayPreviewLowStock() {
    TindaGoTheme {
        SelectedProductDisplay(
            product = Product(
                id = 2,
                name = "Lucky Me Pancit Canton",
                quantity = 3,
                costPrice = 10.0,
                sellingPrice = 14.0,
                unit = "pack",
                brand = "Lucky Me",
                packageSize = "80g",
                category = "instant_dry_goods",
                subcategory = "instant_noodles"
            ),
            onClear = {},
            lang = "en"
        )
    }
}

@Preview(showBackground = true, name = "SelectedProductDisplay - Out of Stock")
@Composable
fun SelectedProductDisplayPreviewOutOfStock() {
    TindaGoTheme {
        SelectedProductDisplay(
            product = Product(
                id = 3,
                name = "Bear Brand Milk 330ml",
                quantity = 0,
                costPrice = 25.0,
                sellingPrice = 35.0,
                unit = "can",
                brand = "Bear Brand",
                packageSize = "330ml",
                category = "dairy_refrigerated",
                subcategory = "powdered_milk"
            ),
            onClear = {},
            lang = "en"
        )
    }
}

