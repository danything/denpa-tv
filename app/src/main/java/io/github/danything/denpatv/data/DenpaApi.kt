package io.github.danything.denpatv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI

/**
 * denpa の外向けの口 (docs/api.md) を叩く。
 *
 * **ログインは持たない。** テレビは家の LAN (denpa の TRUSTED_NETWORKS) に居る前提で、
 * そこからはログイン無しで通る。外から繋ぐ形はまだ扱わない
 */
class DenpaApi {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(base: URI): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/health") ?: return@withContext false
        try {
            Http.request(url).ok
        } catch (_: IOException) {
            false
        }
    }

    suspend fun services(base: URI): List<Service> = get(base, "api/services")

    suspend fun recordings(base: URI, limit: Int = 50): List<Recording> = get(base, "api/recordings?limit=$limit")

    /**
     * どこまで観たかを預ける (`POST /api/recordings/<id>/resume`)。秒で渡す。
     * 末尾まで観たものは denpa が消す (次に開いたときエンドロールから始まらないように)
     */
    suspend fun saveResume(base: URI, id: Long, atSeconds: Double, lengthSeconds: Double) =
        withContext(Dispatchers.IO) {
            val url = BaseUrl.resolve(base, "api/recordings/$id/resume") ?: return@withContext
            try {
                Http.request(url, "POST", """{"at":$atSeconds,"length":$lengthSeconds}""")
            } catch (_: IOException) {
                // 覚えられなくても観るのは止めない
            }
        }

    /** 録画を消す (`DELETE /api/recordings/<id>`。消えれば 204)。消せたら true */
    suspend fun deleteRecording(base: URI, id: Long): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/recordings/$id") ?: return@withContext false
        try {
            Http.request(url, "DELETE").ok
        } catch (_: IOException) {
            false
        }
    }

    private suspend inline fun <reified T> get(base: URI, path: String): T = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, path) ?: throw IOException("URL を組み立てられません: $path")
        json.decodeFromString<T>(Http.get(url))
    }
}
