# kotlinx.serialization はコンパイラプラグインで直列化コードを作るので、反射用の keep は要らない。
# R8 の既定の規則 (ライブラリ同梱の consumer rules) で足りる

# HttpEngine に触るのは EngineHttp の中だけにする (denpa-tv#24)。R8 が factory() を呼ぶ側へ
# 畳み込むと、HttpEngine の無い端末でも読み込まれる場所に HttpEngine への参照が出てしまう
-keep,allowobfuscation class io.github.danything.denpatv.data.EngineHttp { *; }
