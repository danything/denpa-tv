# kotlinx.serialization はコンパイラプラグインで直列化コードを作るので、反射用の keep は要らない。
# R8 の既定の規則 (ライブラリ同梱の consumer rules) で足りる
