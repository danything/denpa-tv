package io.github.danything.denpatv.smoke

/**
 * 偽の denpa が返す録画の字幕 (`GET api/recordings/<id>/captions.json`。denpa の docs/api.md「字幕の文字の配置」)。
 * 面は放送と同じ 960x540、区画は 40x60 (libaribcaption と同じ置き方。下の2行は y = 390・450 あたり)
 */
internal object FakeCaptions {
    /** smoke が出たかを見る字 (読み上げの文) と、背景の色 (画面の絵で塗られたかを見る。映像の地の赤紫と違う青) */
    const val TEXT = "偽の字幕"
    const val BACKGROUND = 0xFF0000FF.toInt()

    /** 頭から出し続ける1枚 */
    val smoke = pages(
        """{"x":330,"y":450,"w":40,"h":60,"fx":2,"fy":12,"size":36,"scaleX":1,"text":"$TEXT","fg":"#ffffffff","bg":"#0000ffff"}""",
    )

    /**
     * 画面の絵に撮る1枚 (作り物)。上の行は縁取りだけ (背景なし) で、頭に外字 (音符の点の絵)、間に半角の英字。
     * 下の行は半透明の背景に、黄色の囲み・ルビ (小さな字の行) つき
     */
    val showcase = pages(
        """{"x":370,"y":330,"w":40,"h":60,"fx":2,"fy":12,"size":36,"scaleX":1,"text":"〓","drcs":"1","fg":"#ffffffff","bg":"#00000000","stroke":"#000000ff"}""",
        """{"x":410,"y":330,"w":40,"h":60,"fx":2,"fy":12,"size":36,"scaleX":1,"text":"今夜は","fg":"#ffffffff","bg":"#00000000","stroke":"#000000ff"}""",
        """{"x":530,"y":330,"w":20,"h":60,"fx":1,"fy":12,"size":36,"scaleX":1,"text":"TV","fg":"#ffffffff","bg":"#00000000","stroke":"#000000ff"}""",
        """{"x":570,"y":330,"w":40,"h":60,"fx":2,"fy":12,"size":36,"scaleX":1,"text":"で","fg":"#ffffffff","bg":"#00000000","stroke":"#000000ff"}""",
        """{"x":570,"y":420,"w":20,"h":30,"fx":1,"fy":6,"size":18,"scaleX":1,"text":"えが","fg":"#ffffffff","bg":"#00000080","ruby":true}""",
        """{"x":330,"y":450,"w":40,"h":60,"fx":2,"fy":12,"size":36,"scaleX":1,"text":"字幕を","fg":"#ffffffff","bg":"#00000080"}""",
        """{"x":450,"y":450,"w":40,"h":60,"fx":2,"fy":12,"size":36,"scaleX":1,"text":"文字","fg":"#ffff00ff","bg":"#00000080","box":15}""",
        """{"x":530,"y":450,"w":40,"h":60,"fx":2,"fy":12,"size":36,"scaleX":1,"text":"で描く","fg":"#ffffffff","bg":"#00000080"}""",
        drcs = """{"1":{"w":16,"h":16,"depth":2,"bits":1,"data":"D/wP/AwMDAwMDAwMDAwMDAwMDAw8PHx8/Pz8/Hh4MDA="}}""",
    )

    private fun pages(vararg runs: String, drcs: String = "{}") =
        """{"v":1,"pages":[{"at":0,"page":{"v":1,"plane":[960,540],"duration":null,"runs":[${runs.joinToString(",")}],"drcs":$drcs}}]}"""
}
