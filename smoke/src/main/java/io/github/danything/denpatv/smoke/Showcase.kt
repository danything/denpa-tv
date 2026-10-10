package io.github.danything.denpatv.smoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 画面の絵を撮るときの、作り物の録画 (`Screenshots`)。README の絵と同じく、実際の放送にありそうな長い題・局・観た割合・
 * 録画中・エンコード中を並べる。ポスターはその場で描く (空と日と影)。時刻は端末の時間帯で組む (出る時刻が決まるように)
 */
internal object Showcase {
    private class Item(
        val id: Long,
        val title: String,
        val service: String,
        /** 放送日 (2026年10月の日) と時刻 */
        val day: Int,
        val hour: Int,
        val minute: Int,
        val minutes: Int = 30,
        /** 続きの位置 (長さに対する割合) */
        val resume: Float? = null,
        /** 観終えたか (無ければ未視聴の点) */
        val watched: Boolean = false,
        val recording: Boolean = false,
        val description: String = "",
    )

    /** 焼いている最中 (知らせで進みを送る) の録画と、その進み */
    const val ENCODING_ID = 102L
    const val ENCODING_PERCENT = 0.42

    /** 字幕を撮る録画 (続きが無いので頭から流れる) */
    const val CAPTION_ID = 104L

    /** 長い題 (撮るときに合わせる) */
    const val LONG_TITLE = "凶乱令嬢ニア・リストン 病弱令嬢に転生した神殺しの武人の華麗なる無双録 #1"

    private val items = listOf(
        Item(101, "辺境に追放された薬師令嬢は、王都で最強の錬金術師になる #3", "AT-X", 7, 15, 0, recording = true),
        Item(ENCODING_ID, "まち歩き紀行「川越 蔵の町をゆく」", "ＮＨＫ総合１・東京", 7, 10, 5, minutes = 45, description = "蔵造りの町並みが残る川越を歩く。時の鐘の下で、老舗の菓子屋と鍛冶屋を訪ねる。"),
        Item(103, "きょうの台所「秋の炊き込みごはん」", "ＮＨＫＥテレ１東京", 7, 9, 0, minutes = 25, watched = true),
        Item(104, "追放された荷物持ちは、実は最強の鍛冶師でした #1", "ＴＯＫＹＯ　ＭＸ１", 7, 1, 5, description = "荷物持ちとして勇者の一行を追い出されたロイドは、辺境の村で鍛冶屋を開く。打った剣が評判を呼び、やがて王都から使者が訪れる。"),
        Item(105, "星読みの薬師 #18「月下の花」", "日テレ", 6, 23, 0, resume = 0.4f),
        Item(106, "星降る街の図書館 #5", "ＢＳ１１イレブン", 6, 22, 0, resume = 1f),
        Item(107, "サイエンス最前線「深海の生きもの」", "ＮＨＫ総合１・東京", 6, 20, 0, minutes = 50, resume = 0.3f),
        Item(108, "鉄道の旅 ~ローカル線をゆく~ 只見線", "ＢＳ日テレ", 6, 19, 0, minutes = 55),
        Item(109, LONG_TITLE, "ＴＯＫＹＯ　ＭＸ１", 5, 23, 30, description = "神殺しの武人が転生したのは、病弱で余命わずかな貴族の令嬢だった。"),
        Item(110, "追放されたチート付与魔術師は気ままなセカンドライフを謳歌する。 #2", "ＢＳ１１イレブン", 5, 23, 0),
        Item(111, "片田舎のおっさん、剣聖になる #7「べリルの選択」", "ＴＢＳ", 5, 1, 28, watched = true),
        Item(112, "水曜日のダウンタウン", "ＴＢＳ", 5, 22, 0, minutes = 60, resume = 0.75f),
        Item(113, "世界ふれあい街歩き「リスボン」", "ＮＨＫ　ＢＳ", 4, 19, 30, minutes = 45),
        Item(114, "ニュースウオッチ9", "ＮＨＫ総合１・東京", 4, 21, 0, minutes = 60, watched = true),
        Item(115, "ダンジョン飯 第2期 第8話「炎竜の巣」", "ＴＯＫＹＯ　ＭＸ１", 4, 22, 30),
        Item(116, "日本の名城をめぐる旅 (39)「姫路城 白鷺の城の四季」", "ＢＳ朝日１", 4, 18, 0, minutes = 55),
    )

    fun recordings(): String = items.joinToString(",", "[", "]") { item ->
        val start = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, item.day, item.hour, item.minute) }.timeInMillis
        val length = item.minutes * 60_000L
        val files = if (item.recording) "[]" else
            """[{"source":"encoded","codec":"av1","url":"api/recordings/${item.id}/file"},
            {"source":"alt","codec":"h264","url":"api/recordings/${item.id}/file"},
            {"source":"ts","codec":"mpeg2","url":"api/recordings/${item.id}/file"}]"""
        """{"id":${item.id},"title":"${item.title}","serviceName":"${item.service}","startAt":$start,"durationMs":$length,
        "endAt":${start + length},"poster":"api/recordings/${item.id}/poster","files":$files,"recording":${item.recording},
        "resumeMs":${item.resume?.let { (it * length).toLong() }},"watchedAt":${if (item.watched) start + length else "null"}}"""
    }

    fun has(id: Long) = items.any { it.id == id }

    fun detail(id: Long): String {
        val text = items.firstOrNull { it.id == id }?.description.orEmpty()
        return """{"description":"$text","extended":{}}"""
    }

    private val posters = ConcurrentHashMap<Long, ByteArray>()

    /** 録画のポスター (JPEG、640×360)。録画ごとに空の色・日の位置・影の形を変える */
    fun poster(id: Long): ByteArray = posters.getOrPut(id) {
        val seed = (id * 2654435761L).toInt()
        fun pick(n: Int, shift: Int) = Math.floorMod(seed shr shift, n)
        val (top, bottom) = SKIES[pick(SKIES.size, 3)]
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null
        paint.color = SUN
        canvas.drawCircle(80f + pick(480, 7), 60f + pick(90, 13), 34f, paint)
        // 遠くの丘と、近くの影 (木と家)
        for ((layer, color) in HILLS.withIndex()) {
            paint.color = color
            val base = HEIGHT * (0.62f + 0.12f * layer)
            val path = Path().apply {
                moveTo(0f, HEIGHT.toFloat())
                lineTo(0f, base)
                var x = 0f
                var i = 0
                while (x <= WIDTH) {
                    lineTo(x, base - 18f - Math.floorMod(seed shr (i + layer * 5), 40))
                    x += 64f
                    i++
                }
                lineTo(WIDTH.toFloat(), HEIGHT.toFloat())
                close()
            }
            canvas.drawPath(path, paint)
            repeat(3) { n ->
                val cx = 60f + Math.floorMod(seed shr (n * 4 + layer), WIDTH - 120)
                canvas.drawCircle(cx, base - 30f, 26f + 8 * layer, paint)
                canvas.drawRect(cx - 4, base - 30f, cx + 4, base + 10f, paint)
            }
        }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val SUN = 0xFFF6D27A.toInt()
    private val SKIES = listOf(
        0xFF8EC5E8.toInt() to 0xFFE8D7B8.toInt(),
        0xFF2B2F5C.toInt() to 0xFF6C5B8F.toInt(),
        0xFFF2C9A0.toInt() to 0xFFE89A7A.toInt(),
        0xFF5E8FB8.toInt() to 0xFFBFD8E6.toInt(),
        0xFF1C2433.toInt() to 0xFF3A4A66.toInt(),
        0xFFE8B4A0.toInt() to 0xFF9A7AA0.toInt(),
    )
    private val HILLS = listOf(0xCC3C5A4A.toInt(), 0xEE1F2B26.toInt())
}
