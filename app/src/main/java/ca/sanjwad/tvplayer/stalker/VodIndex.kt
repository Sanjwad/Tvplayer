package ca.sanjwad.tvplayer.stalker

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * A copy of the portal's whole movie and show list, kept on the TV. Stalker portals search slowly
 * (100,000 titles: the portal's own search times out), so Search looks here instead.
 */
class VodIndex(file: File) {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        execSQL(
            "CREATE TABLE IF NOT EXISTS vod(id TEXT PRIMARY KEY, name TEXT, norm TEXT, descr TEXT, poster TEXT, " +
                "cmd TEXT, series INTEGER, year TEXT, rating TEXT, cat TEXT)"
        )
        execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    /** [norm] is the searchable name with a space either side, so words can be matched from their start. */
    @Synchronized
    fun put(items: List<Pair<VodItem, String>>) {
        if (items.isEmpty()) return
        db.beginTransaction()
        try {
            for ((v, cat) in items) {
                db.insertWithOnConflict("vod", null, ContentValues().apply {
                    put("id", v.id); put("name", v.name); put("norm", " ${TitleMatch.norm(v.name)} ")
                    put("descr", v.description); put("poster", v.poster); put("cmd", v.cmd)
                    put("series", if (v.isSeries) 1 else 0); put("year", v.year); put("rating", v.rating); put("cat", cat)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun has(id: String): Boolean =
        db.rawQuery("SELECT 1 FROM vod WHERE id = ? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    @Synchronized
    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM vod", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun meta(k: String): String? =
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized
    fun setMeta(k: String, v: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Titles whose name matches exactly (ignoring case and punctuation), keyed by that normalised name. */
    @Synchronized
    fun exact(norms: Collection<String>): Map<String, List<VodItem>> {
        val keys = norms.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, MutableList<VodItem>>()
        for (chunk in keys.chunked(400)) {
            val q = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT id, name, descr, poster, cmd, series, year, rating, norm FROM vod WHERE norm IN ($q)",
                chunk.map { " $it " }.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    out.getOrPut(c.getString(8).trim()) { mutableListOf() } += VodItem(
                        c.getString(0), c.getString(1), c.getString(2).orEmpty(), c.getString(3)?.ifBlank { null },
                        c.getString(4).orEmpty(), c.getInt(5) == 1, c.getString(6).orEmpty(), c.getString(7).orEmpty(), null,
                    )
                }
            }
        }
        return out
    }

    /** Titles containing every word (each matched from the start of a word, punctuation ignored). */
    @Synchronized
    fun search(query: String, limit: Int = 120): List<VodItem> {
        val words = TitleMatch.norm(query).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "norm LIKE ?" }
        val args = words.map { "% $it%" }.toTypedArray()
        // Exact-ish matches first (shorter names), then newest.
        return db.rawQuery(
            "SELECT id, name, descr, poster, cmd, series, year, rating FROM vod WHERE $where ORDER BY length(norm), rowid DESC LIMIT $limit",
            args,
        ).use { c ->
            val out = mutableListOf<VodItem>()
            while (c.moveToNext()) {
                out += VodItem(
                    c.getString(0), c.getString(1), c.getString(2).orEmpty(), c.getString(3)?.ifBlank { null },
                    c.getString(4).orEmpty(), c.getInt(5) == 1, c.getString(6).orEmpty(), c.getString(7).orEmpty(), null,
                )
            }
            out
        }
    }
}
