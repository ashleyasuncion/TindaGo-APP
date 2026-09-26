"""
Remove the web `search-control` field (inventory.html -> #searchControl) from the
mobile app's Inventory screen (StocksScreen.kt) and clean up everything that
dangled off it.

Run:  python _remove_search_control.py
"""
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "git", "app", "TindaGo_APP")
JAVA = os.path.join(APP, "app", "src", "main", "java", "com", "example", "tindago")

failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print("FAIL: " + msg)
    else:
        print("ok  : " + msg)
    return cond


def load(path):
    with open(path, "rb") as fh:
        return fh.read().decode("utf-8")


def save(path, text):
    with open(path, "wb") as fh:
        fh.write(text.encode("utf-8"))


def find(lines, needle, start=0):
    for i in range(start, len(lines)):
        if needle in lines[i]:
            return i
    raise SystemExit("anchor not found: %r" % needle)


def find_exact(lines, content, start=0):
    for i in range(start, len(lines)):
        if lines[i].rstrip("\r\n") == content:
            return i
    raise SystemExit("exact line not found: %r" % content)


def newline_of(text):
    return "\r\n" if "\r\n" in text else "\n"


def replace_line(lines, needle, new_line, nl):
    i = find(lines, needle)
    lines[i] = new_line + nl
    return i


# ─────────────────────────────────────────────────────────────────────────────
# 1. StocksScreen.kt
# ─────────────────────────────────────────────────────────────────────────────
p_stocks = os.path.join(JAVA, "ui", "screens", "StocksScreen.kt")
text = load(p_stocks)
nl = newline_of(text)
lines = text.splitlines(keepends=True)
check(len(lines) == 421, "StocksScreen.kt read (421 lines)")

# (a) unused Search icon import
i = find(lines, "import androidx.compose.material.icons.filled.Search")
del lines[i]

# (b) searchQuery / searchMode state + reset LaunchedEffect
s = find(lines, 'var searchQuery by remember { mutableStateOf("") }')
e = find(lines, 'searchQuery = ""', s)
check(lines[e + 1].strip() == "}", "LaunchedEffect(Unit) closes after searchQuery reset")
lines[s:e + 2] = [
    "    // NOTE: the web inventory.html `search-control` field (category dropdown +" + nl,
    "    // morphing search input) was removed from this screen. Category filtering is" + nl,
    "    // now done with the chips row below, so no searchQuery/searchMode state is kept." + nl,
]

# (c) filteredProducts no longer runs a text search
s = find(lines, "val filteredProducts = remember(products, searchQuery,")
f = find(lines, "searched.any { it.id == p.id }", s)
e = find(lines, "            searched", f)
check(lines[e + 1].strip() == "}", "filteredProducts else-branch closes with }")
lines[s:e + 2] = [
    "    val filteredProducts = remember(products, selectedCategory, selectedSubcategory, specificSales, viewModel.today, sortByForecast) {" + nl,
    "        // No text search: the search-control field was removed, so the" + nl,
    "        // category / subcategory chips are the only inventory filter." + nl,
    "        val byCategory = if (selectedCategory.isNotBlank() || selectedSubcategory.isNotBlank()) {" + nl,
    "            viewModel.getProductsBySubcategory(selectedCategory, selectedSubcategory)" + nl,
    "        } else {" + nl,
    "            products" + nl,
    "        }" + nl,
]

# (d) back-to-top comment
replace_line(
    lines,
    "(search + cat chips + add button)",
    "    // Show back-to-top when scrolled past ~3 items (cat chips + add + restock buttons).",
    nl,
)

# (e) the CategorySearchField item itself
s = find(lines, "Search bar / Category Search Field")
t = find(lines, 'tutorialHighlight("stockSearchBar", highlightState)', s)
check(lines[t + 1].strip() == ")", "CategorySearchField args close with )")
check(lines[t + 2].strip() == "}", "search item block closes with }")
check(lines[s - 1].strip() == "item {", "search block starts with 'item {'")
del lines[s - 1:t + 3]

# (f) "No items match your search." empty state (unreachable without a query)
s = find(lines, "filteredProducts.isEmpty() && searchQuery.isNotBlank()")
t = find(lines, "No items match your search.", s)
e = find_exact(lines, "        }", t + 1)
del lines[s:e + 1]

# (g) the remaining empty state is now the only one
i = find(lines, "filteredProducts.isEmpty() && searchQuery.isBlank()")
lines[i] = "        if (filteredProducts.isEmpty()) {" + nl

joined = "".join(lines)
check("searchQuery" not in joined, "no searchQuery references left")
check("searchMode" not in joined, "no searchMode references left")
check("stockSearchBar" not in joined, "no stockSearchBar highlight left")
check("CategorySearchField" not in joined, "no CategorySearchField usage left")
save(p_stocks, joined)

# ─────────────────────────────────────────────────────────────────────────────
# 2. TutorialOverlay.kt - stock page tutorial step 2 has no target any more
# ─────────────────────────────────────────────────────────────────────────────
p_overlay = os.path.join(JAVA, "ui", "components", "TutorialOverlay.kt")
text = load(p_overlay)
nl = newline_of(text)
lines = text.splitlines(keepends=True)
i = find(lines, '"stockSearchBar"')
check('PageTutorial("stock"' in lines[i - 1], "stock tutorial highlight line found")
lines[i] = '        highlights = listOf(null, null, "addStockBtn", null, "inventoryList", null, null, null, null, null),' + nl
save(p_overlay, "".join(lines))

# ─────────────────────────────────────────────────────────────────────────────
# 3. NavGraph.kt - main tutorial step 11 = inventory page intro, no target
# ─────────────────────────────────────────────────────────────────────────────
p_nav = os.path.join(JAVA, "ui", "navigation", "NavGraph.kt")
text = load(p_nav)
nl = newline_of(text)
lines = text.splitlines(keepends=True)
i = find(lines, '"tutorial11", "inventory", "stockSearchBar"')
lines[i] = '    TutorialStep("tutorial11", "inventory"),                                // Inventory page (search control removed)' + nl
save(p_nav, "".join(lines))

# ─────────────────────────────────────────────────────────────────────────────
# 4. Strings.kt - copy that described the removed control
# ─────────────────────────────────────────────────────────────────────────────
p_strings = os.path.join(JAVA, "ui", "localization", "Strings.kt")
text = load(p_strings)
nl = newline_of(text)
lines = text.splitlines(keepends=True)
replace_line(
    lines,
    '"stockTutorial2" to "Use the Search bar',
    '        "stockTutorial2" to "Use the category chips to filter the list. Tap the \\"All\\" chip to show every item.",',
    nl,
)
replace_line(
    lines,
    '"stockTutorial2" to "Gamitin ang Search bar',
    '        "stockTutorial2" to "Gamitin ang category chips para i-filter ang listahan. I-tap ang \\"All\\" chip para ipakita ang lahat.",',
    nl,
)
replace_line(
    lines,
    '"tutorial11" to "The Inventory page lets you search,',
    '        "tutorial11" to "The Inventory page lets you add and manage all your stock items in one place.",',
    nl,
)
replace_line(
    lines,
    '"tutorial11" to "Ang Inventory page ay nagbibigay-daan sa iyo na maghanap,',
    '        "tutorial11" to "Ang Inventory page ay nagbibigay-daan sa iyo na magdagdag at mamahala ng stock.",',
    nl,
)
save(p_strings, "".join(lines))

# ─────────────────────────────────────────────────────────────────────────────
# 5. tutorial docs (kept in sync with the code / Strings.kt)
# ─────────────────────────────────────────────────────────────────────────────
p_doc = os.path.join(APP, "tutorials", "stock_tutorial.txt")
text = load(p_doc)
nl = newline_of(text)
lines = text.splitlines(keepends=True)
s = find(lines, "STEP 2 ")
t = find(lines, "  Interaction    : look at the search bar, tap Next", s)
check(t - s == 7, "stock_tutorial step 2 is 8 lines")
lines[s:t + 1] = [
    "STEP 2 — Category chips" + nl,
    "  Key      : stockTutorial2" + nl,
    "  Text EN : \"Use the category chips to filter the list. Tap the \\\"All\\\" chip to show every item.\"" + nl,
    "  Text FIL: \"Gamitin ang category chips para i-filter ang listahan. I-tap ang \\\"All\\\" chip para ipakita ang lahat.\"" + nl,
    "  Target element : none (web search-control field removed from StocksScreen)" + nl,
    "  Highlight      : none" + nl,
    "  Box position   : bottom center" + nl,
    "  Interaction    : read text, tap Next" + nl,
]
save(p_doc, "".join(lines))

p_doc = os.path.join(APP, "tutorials", "main_tutorial.txt")
text = load(p_doc)
nl = newline_of(text)
lines = text.splitlines(keepends=True)
s = find(lines, "STEP 11 ")
t = find(lines, "  Interaction    : look at the search bar, tap Next", s)
check(t - s == 9, "main_tutorial step 11 is 10 lines")
lines[s:t + 1] = [
    "STEP 11 — Inventory page intro" + nl,
    "  Key      : tutorial11" + nl,
    "  Text EN : \"The Inventory page lets you add and manage all your stock items in one place.\"" + nl,
    "  Text FIL: \"Ang Inventory page ay nagbibigay-daan sa iyo na magdagdag at mamahala ng stock.\"" + nl,
    "  Target page    : inventory  (navigates from closing)" + nl,
    "  Target element : none (web search-control field removed from StocksScreen)" + nl,
    "  Highlight      : none" + nl,
    "  Box position   : bottom center" + nl,
    "  Transition     : Next navigates closing -> inventory" + nl,
    "  Interaction    : read text, tap Next" + nl,
]
save(p_doc, "".join(lines))

# ─────────────────────────────────────────────────────────────────────────────
# 6. archive the now unused CategorySearchField.kt outside the source set
# ─────────────────────────────────────────────────────────────────────────────
src = os.path.join(JAVA, "ui", "components", "CategorySearchField.kt")
dst_dir = os.path.join(APP, "_removed")
dst = os.path.join(dst_dir, "CategorySearchField.kt")
if os.path.exists(src):
    os.makedirs(dst_dir, exist_ok=True)
    if os.path.exists(dst):
        os.remove(dst)
    os.replace(src, dst)
    print("moved: CategorySearchField.kt -> _removed/CategorySearchField.kt")
else:
    print("note : CategorySearchField.kt already archived")

# ─────────────────────────────────────────────────────────────────────────────
# 7. progress log entry (mobile app)
# ─────────────────────────────────────────────────────────────────────────────
p_prog = os.path.join(APP, "progress.txt")
pnl = newline_of(load(p_prog))
entry_lines = [
    "",
    "================================================================================",
    "INVENTORY SEARCH-CONTROL REMOVED (MOBILE)",
    "Date: Sept 25, 2026",
    "Status: IMPLEMENTED",
    "",
    "REQUEST:",
    '- User: "remove the [search-control] in the mobile app ver inventory".',
    "  `search-control` / `#searchControl` is the category + search morphing field in",
    "  the web prototype (git/TindaGo/inventory.html). Its Android twin was the",
    "  CategorySearchField composable rendered at the top of StocksScreen.",
    "",
    "SOLUTION (mobile only):",
    "- StocksScreen.kt: removed the CategorySearchField item (with its",
    '  tutorialHighlight("stockSearchBar")), plus the now-dead searchQuery /',
    "  searchMode state, the LaunchedEffect(Unit) reset, the viewModel.searchProducts",
    "  branch in filteredProducts, the \"No items match your search.\" empty state and",
    "  the unused Icons.Default.Search import. Category filtering is unchanged: the",
    "  existing category chips (collapsed LazyRow / expanded FlowRow) are now the",
    "  only inventory filter (web v2.59 renderInventoryCatFilters parity kept).",
    "- CategorySearchField.kt: no longer referenced -> moved to",
    "  git/app/TindaGo_APP/_removed/CategorySearchField.kt (kept for easy restore;",
    "  the subcategory drill-down went away with the field).",
    "- Tutorial wiring kept consistent (same step counts / i18n keys):",
    '  TutorialOverlay.kt stock tutorial highlights[1] "stockSearchBar" -> null;',
    '  NavGraph.kt TutorialStep("tutorial11", "inventory", "stockSearchBar") ->',
    '  TutorialStep("tutorial11", "inventory"); Strings.kt stockTutorial2 (EN+FIL)',
    "  and tutorial11 (EN+FIL) copy no longer mention the search bar;",
    "  tutorials/stock_tutorial.txt + tutorials/main_tutorial.txt updated.",
    "",
    "AFFECTED FILES (mobile, git copy):",
    "- ui/screens/StocksScreen.kt",
    "- ui/components/TutorialOverlay.kt",
    "- ui/navigation/NavGraph.kt",
    "- ui/localization/Strings.kt",
    "- ui/components/CategorySearchField.kt (archived under _removed/)",
    "- tutorials/stock_tutorial.txt, tutorials/main_tutorial.txt",
    "",
    "NOTE:",
    "- The web prototype (git/TindaGo/inventory.html) still renders its own",
    "  #searchControl field - this change is mobile-only, so the two versions now",
    "  differ on the Inventory screen, and the mobile app has no inventory text",
    "  search until a plain search bar is added back.",
    "================================================================================",
]
with open(p_prog, "ab") as fh:
    fh.write((pnl.join(entry_lines) + pnl).encode("utf-8"))
print("appended progress.txt entry")

print("")
if failures:
    print("%d CHECK(S) FAILED" % len(failures))
    sys.exit(1)
print("ALL PATCHES APPLIED")
