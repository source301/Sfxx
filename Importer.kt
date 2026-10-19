package com.sfxreader

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs

enum class Lang(val label: String) {
    KOREAN("كوري + إنجليزي"), JAPANESE("ياباني"), CHINESE("صيني"), LATIN("إنجليزي");

    fun recognizer(): TextRecognizer = when (this) {
        KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}

object Importer {
    private val imgExt = setOf("jpg", "jpeg", "png", "webp")

    private val natural = Comparator<String> { a, b ->
        val re = Regex("\\d+|\\D+")
        val ra = re.findAll(a.lowercase()).map { it.value }.toList()
        val rb = re.findAll(b.lowercase()).map { it.value }.toList()
        var i = 0
        while (i < ra.size && i < rb.size) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            if (c != 0) return@Comparator c
            i++
        }
        ra.size - rb.size
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "chapter"
        }
        return uri.lastPathSegment ?: "chapter"
    }

    suspend fun import(ctx: Context, uris: List<Uri>, lang: Lang, onProgress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val dir = File(Store.root(ctx), "ch_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                onProgress("جاري نسخ الصفحات...")
                val first = uris.first()
                val firstName = displayName(ctx, first)
                val copied = mutableListOf<File>()
                val isZip = uris.size == 1 && firstName.lowercase().let { it.endsWith(".cbz") || it.endsWith(".zip") }

                if (isZip) {
                    val tmp = mutableListOf<Pair<String, File>>()
                    ZipInputStream(BufferedInputStream(ctx.contentResolver.openInputStream(first)!!)).use { zin ->
                        var e = zin.nextEntry
                        var i = 0
                        while (e != null) {
                            val ext = e.name.substringAfterLast('.', "").lowercase()
                            if (!e.isDirectory && ext in imgExt) {
                                val f = File(dir, "tmp_${i++}.$ext")
                                f.outputStream().use { zin.copyTo(it) }
                                tmp += e.name to f
                            }
                            e = zin.nextEntry
                        }
                    }
                    tmp.sortedWith { a, b -> natural.compare(a.first, b.first) }.forEachIndexed { i, (_, f) ->
                        val dest = File(dir, "page_%04d.%s".format(i, f.extension))
                        f.renameTo(dest); copied += dest
                    }
                } else {
                    uris.map { it to displayName(ctx, it) }
                        .filter { it.second.substringAfterLast('.', "jpg").lowercase() in imgExt }
                        .sortedWith { a, b -> natural.compare(a.second, b.second) }
                        .forEachIndexed { i, (uri, name) ->
                            val dest = File(dir, "page_%04d.%s".format(i, name.substringAfterLast('.', "jpg").lowercase()))
                            ctx.contentResolver.openInputStream(uri)!!.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                            copied += dest
                        }
                }
                if (copied.isEmpty()) throw IllegalStateException("ما لقيت صور بالملف")

                File(dir, "name.txt").writeText(firstName.substringBeforeLast('.'))

                val rec = lang.recognizer()
                val all = mutableListOf<Marker>()
                try {
                    copied.forEachIndexed { i, f ->
                        onProgress("تحليل الصفحة ${i + 1} من ${copied.size}...")
                        all += ocrPage(f, i, rec)
                    }
                } finally { rec.close() }

                Store.saveMarkers(dir, all.mapIndexed { i, m -> m.copy(id = i) })
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }

    private suspend fun ocrPage(f: File, page: Int, rec: TextRecognizer): List<Marker> {
        val out = mutableListOf<Marker>()
        try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            val w = b.outWidth; val h = b.outHeight
            if (w <= 0 || h <= 0) return emptyList()
            var sample = 1
            while (w / sample > 1400) sample *= 2

            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(f.path, false) ?: return emptyList()
            try {
                val chunk = 1600 * sample
                var top = 0
                while (top < h) {
                    val bottom = minOf(h, top + chunk)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = decoder.decodeRegion(Rect(0, top, w, bottom), opts) ?: break
                    val res = rec.process(InputImage.fromBitmap(bmp, 0)).await()
                    for (block in res.textBlocks) for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val cat = SfxDictionary.match(line.text) ?: continue
                        out += Marker(0, page, (top + box.centerY() * sample).toFloat() / h, line.text, cat)
                    }
                    bmp.recycle()
                    top = bottom
                }
            } finally { decoder.recycle() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // صفحة فيها مشكلة: نكمل بدونها
        }
        val sorted = out.sortedBy { it.y }
        val result = mutableListOf<Marker>()
        for (m in sorted) if (result.none { it.category == m.category && abs(it.y - m.y) < 0.02f }) result += m
        return result
    }
}
