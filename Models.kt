package com.sfxreader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.content.Context

data class Marker(val id: Int, val page: Int, val y: Float, val text: String, val category: String)
data class Chapter(val id: String, val name: String)
data class ChapterData(val pages: List<File>, val markers: List<Marker>)

object Store {
    fun root(ctx: Context) = File(ctx.filesDir, "chapters").apply { mkdirs() }

    fun list(ctx: Context): List<Chapter> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.map {
            val n = File(it, "name.txt")
            Chapter(it.name, if (n.exists()) n.readText() else it.name)
        } ?: emptyList()

    fun load(ctx: Context, id: String): ChapterData {
        val dir = File(root(ctx), id)
        val pages = dir.listFiles { f -> f.name.startsWith("page_") }?.sortedBy { it.name } ?: emptyList()
        val mf = File(dir, "markers.json")
        val markers = mutableListOf<Marker>()
        if (mf.exists()) {
            val arr = JSONArray(mf.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                markers += Marker(o.getInt("id"), o.getInt("p"), o.getDouble("y").toFloat(), o.getString("t"), o.getString("c"))
            }
        }
        return ChapterData(pages, markers)
    }

    fun saveMarkers(dir: File, markers: List<Marker>) {
        val arr = JSONArray()
        markers.forEach {
            arr.put(JSONObject().put("id", it.id).put("p", it.page).put("y", it.y.toDouble()).put("t", it.text).put("c", it.category))
        }
        File(dir, "markers.json").writeText(arr.toString())
    }

    fun delete(ctx: Context, id: String) { File(root(ctx), id).deleteRecursively() }
}
