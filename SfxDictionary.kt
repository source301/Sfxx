package com.sfxreader

/**
 * قاموس المؤثرات: الفئة -> كلمات. أضف كلمات جديدة بحرية.
 * الكلمات اللاتينية تُطابق بشكل شبه تام (مع تجاهل تكرار الحروف: booooom = boom).
 * الكلمات غير اللاتينية (كوري/ياباني/صيني/عربي) تُطابق إذا وردت داخل نص قصير.
 * الترتيب مهم: أول فئة تطابق هي التي تُختار.
 */
object SfxDictionary {
    private val words: List<Pair<String, List<String>>> = listOf(
        "boom" to listOf("boom", "kaboom", "explosion", "쾅", "콰광", "ドーン", "ドカーン", "轰", "轰隆", "بوم", "بووم", "انفجار"),
        "heartbeat" to listOf("dokidoki", "badum", "thumpthump", "두근", "ドキドキ", "ドキ", "扑通", "دقدق", "نبض"),
        "hit" to listOf("bam", "bang", "punch", "smack", "whack", "slap", "pow", "thud", "thump", "퍽", "빡", "짝", "팍", "쿵", "バキ", "ドガ", "バシ", "パン", "ドン", "啪", "砰", "咚", "طاخ", "بام", "ضربة"),
        "step" to listOf("step", "steps", "stomp", "clop", "footsteps", "저벅", "뚜벅", "ザッ", "コツコツ", "カツカツ", "哒哒", "踏", "خطوات", "طقطق"),
        "door" to listOf("slam", "creak", "knock", "door", "끼익", "덜컥", "똑똑", "ガチャ", "バタン", "コンコン", "吱呀", "طرق", "صرير"),
        "wind" to listOf("whoosh", "woosh", "swish", "fwoosh", "wind", "휘익", "휘잉", "쌩", "ヒュー", "ビュー", "嗖", "呼", "هووش", "ووش"),
        "splash" to listOf("splash", "drip", "plop", "첨벙", "풍덩", "퐁당", "철썩", "ザバ", "ポチャ", "哗啦", "رش", "بلوب"),
        "click" to listOf("click", "snap", "tick", "clack", "딸깍", "찰칵", "톡", "カチ", "カシャ", "咔", "咔嚓", "طق"),
        "bell" to listOf("ding", "dong", "ring", "beep", "bell", "딩동", "띵", "ピンポン", "リーン", "ピピ", "叮", "叮咚", "دينغ", "رنين", "بيب"),
        "crash" to listOf("crash", "smash", "shatter", "crack", "와장창", "쨍", "와르르", "우르르", "ガシャーン", "パリン", "ゴゴゴ", "碎", "哐", "تحطم", "كراش"),
    )

    private fun collapse(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (sb.isEmpty() || sb.last() != c) sb.append(c)
        return sb.toString()
    }

    fun match(raw: String): String? {
        val n = raw.lowercase().filter { it.isLetterOrDigit() }
        // المؤثرات عادة قصيرة، والحوار الطويل نتجاهله
        if (n.isEmpty() || n.length > 10) return null
        val cn = collapse(n)
        for ((cat, kws) in words) for (kw in kws) {
            val ascii = kw.all { it.code < 128 }
            if (ascii) {
                val ck = collapse(kw)
                if (cn == ck || cn == ck + ck) return cat
            } else if (n.contains(kw)) return cat
        }
        return null
    }
}
