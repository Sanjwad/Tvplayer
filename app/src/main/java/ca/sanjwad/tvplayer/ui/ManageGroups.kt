package ca.sanjwad.tvplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.Library
import ca.sanjwad.tvplayer.stalker.Genre

private val separator = Regex("\\s*[-|:]\\s*")

/** "FRENCH - QUEBEC" → "FRENCH"; "NETFLIX | ENGLISH SERIES" → "NETFLIX". */
private fun groupOf(title: String): String =
    if (separator.containsMatchIn(title)) title.split(separator, limit = 2)[0].trim().uppercase() else "OTHER"

/**
 * Some providers mix up their group names: "NETFLIX | ENGLISH MOVIES" full of TV shows in the
 * series section, "APPLE TV+ | ENGLISH SERIES" full of films in the movie section. Each tab shows
 * its own word instead. Display only: ids, hidden groups and sync stay the same.
 */
private val moviesWord = Regex("\\b(MOVIES|MOVIE|FILMS)\\b", RegexOption.IGNORE_CASE)
private val seriesWord = Regex("\\b(SERIES|TV SHOWS)\\b", RegexOption.IGNORE_CASE)

/** id → heading to show. A renamed group that clashes with a real one gets a number ("… SERIES 2"). */
fun groupLabels(section: String, cats: List<Genre>): Map<String, String> {
    val (wrong, right) = when (section) {
        "series" -> moviesWord to "Series"
        "movies" -> seriesWord to "Movies"
        else -> return cats.associate { it.id to it.title }
    }
    val taken = cats.map { it.title.uppercase() }.toMutableSet()
    return cats.associate { c ->
        if (!wrong.containsMatchIn(c.title)) return@associate c.id to c.title
        val base = wrong.replace(c.title) { m -> if (m.value == m.value.uppercase()) right.uppercase() else right }
        var label = base
        var n = 2
        while (label.uppercase() in taken) label = "$base ${n++}"
        taken += label.uppercase()
        c.id to label
    }
}

/** Lets the person hide whole language groups or single categories in one section. */
@Composable
fun ManageGroups(section: String, categories: List<Genre>, onDone: () -> Unit) {
    val cats = categories.filter { it.id != "*" }
    val hiddenNow = Library.hidden[section].orEmpty()
    val labels = groupLabels(section, cats)
    val groups = cats.groupBy { groupOf(it.title) }.toList().sortedBy { it.first }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit groups", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.width(16.dp))
            Text("${cats.count { it.id !in hiddenNow }} of ${cats.size} shown", fontSize = 14.sp, color = TextDim)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("✓  Done", onClick = onDone)
            TvButton("Show all", onClick = { Library.setHidden(section, cats.map { it.id }, false) })
            TvButton("Hide all", onClick = { Library.setHidden(section, cats.map { it.id }, true) })
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (groups.size > 1) {
                item { Text("Whole groups", fontSize = 14.sp, color = TextDim, modifier = Modifier.padding(4.dp)) }
                items(groups, key = { "g_" + it.first }) { (name, members) ->
                    val shown = members.count { it.id !in hiddenNow }
                    val mark = when (shown) { members.size -> "✓"; 0 -> "✕"; else -> "–" }
                    ToggleRow(mark, "$name  (${members.size})", dim = shown == 0) {
                        Library.setHidden(section, members.map { it.id }, hide = shown > 0)
                    }
                }
                item { Text("Single categories", fontSize = 14.sp, color = TextDim, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) }
            }
            items(cats, key = { "c_" + it.id }) { g ->
                val isHidden = g.id in hiddenNow
                ToggleRow(if (isHidden) "✕" else "✓", labels[g.id] ?: g.title, dim = isHidden) {
                    Library.setHidden(section, listOf(g.id), hide = !isHidden)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(mark: String, label: String, dim: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mark, Modifier.width(28.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = if (dim) ErrorRed else Accent)
            Text(label, fontSize = 15.sp, color = if (dim) TextDim else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
