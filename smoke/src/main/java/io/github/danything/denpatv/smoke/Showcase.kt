package io.github.danything.denpatv.smoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 画面の絵を撮るときの、作り物の録画・局・番組 (`Screenshots`)。実際の放送にありそうな長い題・局・観た割合・録画中・エンコード中を並べる。
 * **番組名・人名・説明はどれも作り物** (実在の番組は使わない)。局名だけは実在の名前で、局ロゴは番号を書いただけの札をその場で描く。
 * ポスターもその場で描く (空と日と影)。録画の日は今日から何日前かで組む (エミュレータの時計がずれていても、録っている最中の録画
 * (今) が一覧の頭に来るように)。ライブの番組の時刻も今に合わせる
 */
internal object Showcase {
    private class Item(
        val id: Long,
        val title: String,
        val service: String,
        /** 放送日 (今日から何日前か) と時刻 */
        val daysAgo: Int,
        val hour: Int,
        val minute: Int,
        val minutes: Int = 30,
        /** 続きの位置 (長さに対する割合) */
        val resume: Float? = null,
        /** 観終えたか (無ければ未視聴の点) */
        val watched: Boolean = false,
        /** 録っている最中 (追っかけで観る) */
        val recording: Boolean = false,
        val description: String = "",
        /** 放送の詳細 (見出し → 本文)。詳しくに出る */
        val extended: List<Pair<String, String>> = emptyList(),
    )

    /** 焼いている最中 (知らせで進みを送る) の録画と、その進み */
    const val ENCODING_ID = 102L
    const val ENCODING_PERCENT = 0.42

    /** 録っている最中の録画 (追っかけで観る) */
    const val CHASE_ID = 101L

    /** 字幕を撮る録画 (続きが無いので頭から流れる。最後まで観たときの絵もこれで撮る) */
    const val CAPTION_ID = 104L

    /** 詳しくを撮る録画 (続きがあり、放送の詳細が揃っている) */
    const val DETAIL_ID = 105L

    /** 長い題 (撮るときに合わせる) */
    const val LONG_TITLE = "病弱令嬢に転生した神殺しの武人は、今日も華麗に無双する ～辺境伯家の静かな日々～ #1"

    private val items = listOf(
        Item(CHASE_ID, "辺境に追放された薬師令嬢は、王都で最強の錬金術師になる #3", "ＡＴ－Ｘ", 0, 15, 0, recording = true),
        Item(ENCODING_ID, "まち歩き紀行「川越 蔵の町をゆく」", "ＮＨＫ総合１・東京", 1, 10, 5, minutes = 45, description = "蔵造りの町並みが残る川越を歩く。時の鐘の下で、老舗の菓子屋と鍛冶屋を訪ねる。"),
        Item(103, "きょうの台所「秋の炊き込みごはん」", "ＮＨＫＥテレ１東京", 1, 9, 0, minutes = 25, watched = true),
        Item(CAPTION_ID, "追放された荷物持ちは、実は最強の鍛冶師でした #1", "ＴＯＫＹＯ　ＭＸ１", 1, 1, 5, description = "荷物持ちとして勇者の一行を追い出されたロイドは、辺境の村で鍛冶屋を開く。打った剣が評判を呼び、やがて王都から使者が訪れる。"),
        Item(
            DETAIL_ID, "星読みの薬師 #18「月下の花」", "日テレ", 2, 23, 0, resume = 0.4f,
            description = "月に一度だけ咲く花を求めて、リセは北の峠へ向かう。",
            extended = listOf(
                "番組内容" to "月に一度だけ咲く花を求めて、リセは北の峠へ向かう。道中で出会った旅の楽師ユノは、花の咲く夜にだけ聞こえる歌を探していた。" +
                    "星の巡りを読み違えたリセは、峠の小屋で一夜を明かすことになる。",
                "出演者" to "リセ：春野ことり\nユノ：秋山そら\n老いた薬師：冬木まもる\nナレーション：夏川しずく",
                "原作・脚本" to "原作：月島あかり「星読みの薬師」(星見書房刊)\n脚本：水無瀬ゆう",
                "制作" to "監督：森野かける\nアニメーション制作：スタジオ星灯り",
                "主題歌" to "オープニング「月下の花」うた：ほしのね",
                "おしらせ" to "次回は1週お休みし、10月20日に放送します。",
            ),
        ),
        Item(106, "星降る街の図書館 #5", "ＢＳ１１イレブン", 2, 22, 0, resume = 1f),
        Item(107, "サイエンス最前線「深海の生きもの」", "ＮＨＫ総合１・東京", 2, 20, 0, minutes = 50, resume = 0.3f),
        Item(108, "鉄道の旅 ~ローカル線をゆく~ 山あいの小さな駅", "ＢＳ日テレ", 2, 19, 0, minutes = 55),
        Item(109, LONG_TITLE, "ＴＯＫＹＯ　ＭＸ１", 3, 23, 30, description = "神殺しの武人が転生したのは、病弱で余命わずかな貴族の令嬢だった。"),
        Item(110, "追放された付与魔術師は、気ままな旅暮らしを満喫する #2", "ＢＳ１１イレブン", 3, 23, 0),
        Item(111, "山里の木こり、剣聖になる #7「森の選択」", "ＴＢＳ", 3, 1, 28, watched = true),
        Item(112, "夜のバラエティ「街角クイズ王」", "ＴＢＳ", 3, 22, 0, minutes = 60, resume = 0.75f),
        Item(113, "世界の路地を歩く「リスボン」", "ＮＨＫ　ＢＳ", 4, 19, 30, minutes = 45),
        Item(114, "ニュース21", "ＮＨＫ総合１・東京", 4, 21, 0, minutes = 60, watched = true),
        Item(115, "迷宮ごはん 第8話「炎の竜の巣」", "ＴＯＫＹＯ　ＭＸ１", 4, 22, 30),
        Item(116, "日本の名城をめぐる旅 (39)「白鷺の城の四季」", "ＢＳ朝日１", 4, 18, 0, minutes = 55),
    )

    fun recordings(): String = items.joinToString(",", "[", "]") { item ->
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -item.daysAgo)
            set(Calendar.HOUR_OF_DAY, item.hour)
            set(Calendar.MINUTE, item.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val length = item.minutes * 60_000L
        val files = if (item.recording) "[]" else
            """[{"source":"encoded","codec":"av1","url":"api/recordings/${item.id}/file"},
            {"source":"alt","codec":"h264","url":"api/recordings/${item.id}/file"},
            {"source":"ts","codec":"mpeg2","url":"api/recordings/${item.id}/file"}]"""
        // 録っている最中のものは、今まで続いている (追っかけのシークバーは録れたところまで)
        val (from, to) = if (item.recording) System.currentTimeMillis().let { it - 12 * 60_000L to it + 18 * 60_000L } else start to start + length
        """{"id":${item.id},"title":${q(item.title)},"serviceName":${q(item.service)},"startAt":$from,"durationMs":${to - from},
        "endAt":$to,"poster":"api/recordings/${item.id}/poster","files":$files,"recording":${item.recording},
        ${if (item.recording) "\"chase\":\"api/recordings/${item.id}/chase\"," else ""}
        "resumeMs":${item.resume?.let { (it * length).toLong() }},"watchedAt":${if (item.watched) start + length else "null"}}"""
    }

    fun detail(id: Long): String {
        val item = items.firstOrNull { it.id == id }
        return """{"description":${q(item?.description.orEmpty())},"extended":${obj(item?.extended.orEmpty())}}"""
    }

    // ---- ライブ (局と、いま放送中の番組) ----

    private class Station(
        val sid: Int,
        val type: String,
        val name: String,
        /** 地上波のリモコン番号 (BS・CS は id から3桁の番号が出る) */
        val key: Int?,
        /** いまの番組と、始まってから・終わるまでの分 */
        val title: String,
        val since: Int,
        val left: Int,
        /** 局ロゴの札の色 */
        val color: Int,
        val reserved: Boolean = false,
        val recording: Boolean = false,
    ) {
        /** denpa の局の id (ネットワーク × 100000 + サービス ID) */
        val id: Long = (when (type) { "GR" -> 32736L; "BS" -> 4L; else -> 7L }) * 100_000L + sid
        val number: Int get() = key ?: sid
    }

    private val stations = listOf(
        Station(1024, "GR", "ＮＨＫ総合１・東京", 1, "ニュース21", 25, 35, 0xFF2F5FA8.toInt()),
        Station(1032, "GR", "ＮＨＫＥテレ１東京", 2, "やさしい理科「月の満ち欠け」", 5, 20, 0xFF2E8B57.toInt()),
        Station(1040, "GR", "日テレ", 4, "週末シネマ「遠い灯台」", 50, 70, 0xFFE0A21B.toInt()),
        Station(
            1064, "GR", "テレビ朝日", 5, DRAMA_TITLE, 38, 32, 0xFFD2483C.toInt(), reserved = true, recording = true,
        ),
        Station(1048, "GR", "ＴＢＳ", 6, "世界ふしぎ探訪「霧の湖」", 12, 48, 0xFF2C3E91.toInt(), reserved = true),
        Station(1072, "GR", "テレビ東京", 7, "旅する食卓「港町の朝ごはん」", 20, 10, 0xFF3A8FD0.toInt()),
        Station(1056, "GR", "フジテレビ", 8, "スポーツ・ダイジェスト", 8, 22, 0xFFE2583A.toInt()),
        Station(23608, "GR", "ＴＯＫＹＯ　ＭＸ１", 9, "夕方ライブ「街の声」", 40, 80, 0xFF5A5F66.toInt()),
        Station(101, "BS", "ＮＨＫ　ＢＳ", null, "山の音楽会「秋の湖畔で」", 15, 45, 0xFF2F5FA8.toInt()),
        Station(141, "BS", "ＢＳ日テレ", null, "鉄道の旅 ~ローカル線をゆく~", 30, 25, 0xFFE0A21B.toInt()),
        Station(151, "BS", "ＢＳ朝日１", null, "日本の名城をめぐる旅 (40)", 10, 45, 0xFFD2483C.toInt()),
        Station(161, "BS", "ＢＳ－ＴＢＳ", null, "昭和の歌謡ショー", 45, 15, 0xFF2C3E91.toInt()),
        Station(211, "BS", "ＢＳ１１イレブン", null, "星降る街の図書館 #6", 3, 27, 0xFF7A4FB0.toInt()),
        Station(333, "CS", "ＡＴ－Ｘ", null, "辺境に追放された薬師令嬢は、王都で最強の錬金術師になる #4", 18, 12, 0xFF7A4FB0.toInt()),
        Station(257, "CS", "日テレジータス", null, "野球中継「秋の決戦」", 60, 120, 0xFFE0A21B.toInt()),
    )

    fun services(): String {
        val now = System.currentTimeMillis()
        return stations.joinToString(",", "[", "]") { s ->
            """{"id":${s.id},"type":"${s.type}","name":${q(s.name)},"remoteControlKey":${s.key},"logo":"api/services/${s.id}/logo",
            "live":"api/services/${s.id}/live","now":{"id":${s.id * 10},"title":${q(s.title)},"startAt":${now - s.since * 60_000L},
            "endAt":${now + s.left * 60_000L},"audios":[],"reserved":${s.reserved},"recording":${s.recording}}}"""
        }
    }

    fun hasService(id: Long) = stations.any { it.id == id }

    /** ライブの映像。局ごとに色の違う3本を回す (`scripts/smoke-media.sh`) */
    fun liveAsset(id: Long): String = "showcase-live-${stations.indexOfFirst { it.id == id }.coerceAtLeast(0) % 3 + 1}.mp4"

    /** 番組表の番組の中身 (`api/programs/<局の id × 10>`)。ドラマだけ放送の詳細まで揃える。ほかは番組名と局だけ */
    fun program(id: Long): String? {
        val s = stations.firstOrNull { it.id * 10 == id } ?: return null
        val drama = s.title == DRAMA_TITLE
        val extended = if (!drama) emptyList() else listOf(
            "番組内容①" to "町の小さな法律事務所を舞台に、弁護士・朝倉と新人事務員の凛が、依頼人それぞれの「小さな正義」に向き合うリーガルドラマ。",
            "番組内容②" to "第3話「約束の鍵」依頼人の老婦人・千代が探しているのは、亡き夫が遺した古い鍵の行方。朝倉と凛は町の古道具屋や銭湯を訪ね歩くうちに、" +
                "二十年前に起きた火事と、そこで交わされたひとつの約束に行き当たる。一方、所長の岩田は事務所の立ち退きを迫られていた。",
            "出演者" to "朝倉 透…青葉 しげる\n水野 凛…白石 ひなた\n千代…紅林 すみれ\n岩田 豊…黒田 いわお",
            "スタッフ" to "脚本：灯野 ゆかり\n演出：橋本 わたる\n音楽：鈴鳴 かなで",
            "主題歌" to "「約束の鍵」うた：ルミナリエ",
        )
        val genre = if (drama) """[{"lv1":3,"lv2":0}]""" else "[]"
        val audios = if (drama) """[{"componentType":3,"langs":["jpn"]},{"componentType":3,"langs":["jpn"],"text":"解説ステレオ"}]""" else """[{"componentType":3,"langs":["jpn"]}]"""
        val description = if (drama) "月曜九時のドラマ。第3話「約束の鍵」" else ""
        return """{"name":${q(s.title)},"service_name":${q(s.name)},"description":${q(description)},"extended":${obj(extended)},
            "genre_detail":$genre,"audios":$audios,"video_type":"mpeg2","video_resolution":"1080i","is_free":true}"""
    }

    private val logos = ConcurrentHashMap<Long, ByteArray>()

    /** 局ロゴ (PNG、128×72)。放送のロゴは使わず、局の番号を書いただけの札 */
    fun logo(id: Long): ByteArray? {
        val s = stations.firstOrNull { it.id == id } ?: return null
        return logos.getOrPut(id) {
            val bitmap = Bitmap.createBitmap(LOGO_WIDTH, LOGO_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = s.color
            canvas.drawRoundRect(RectF(4f, 4f, LOGO_WIDTH - 4f, LOGO_HEIGHT - 4f), 12f, 12f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = if (s.number < 10) 50f else 40f
            val baseline = LOGO_HEIGHT / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(s.number.toString(), LOGO_WIDTH / 2f, baseline, paint)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
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

    /** JSON の文字列 */
    private fun q(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('"')
    }

    private fun obj(pairs: List<Pair<String, String>>): String = pairs.joinToString(",", "{", "}") { (k, v) -> "${q(k)}:${q(v)}" }

    /** 録画中で、詳しくに放送の詳細まで揃えた番組 (ライブの詳しくを撮る) */
    private const val DRAMA_TITLE = "ドラマ「月曜九時」第3話"

    private const val LOGO_WIDTH = 128
    private const val LOGO_HEIGHT = 72
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
