package io.github.danything.denpatv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * denpa の外向けの口 (docs/api.md) を叩く。
 *
 * **ログインは持たない。** テレビは家の LAN (denpa の TRUSTED_NETWORKS) に居る前提で、
 * そこからはログイン無しで通る。外から繋ぐ形はまだ扱わない
 */
class DenpaApi(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(base: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/health") ?: return@withContext false
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    suspend fun services(base: HttpUrl): List<Service> = get(base, "api/services")

    suspend fun recordings(base: HttpUrl, limit: Int = 50): List<Recording> = get(base, "api/recordings?limit=$limit")

    /**
     * どこまで観たかを預ける (`POST /api/recordings/<id>/resume`)。秒で渡す。
     * 末尾まで観たものは denpa が消す (次に開いたときエンドロールから始まらないように)
     */
    suspend fun saveResume(base: HttpUrl, id: Long, atSeconds: Double, lengthSeconds: Double) =
        withContext(Dispatchers.IO) {
            val url = BaseUrl.resolve(base, "api/recordings/$id/resume") ?: return@withContext
            val body = """{"at":$atSeconds,"length":$lengthSeconds}""".toRequestBody("application/json".toMediaType())
            try {
                client.newCall(Request.Builder().url(url).post(body).build()).execute().close()
            } catch (_: IOException) {
                // 覚えられなくても観るのは止めない
            }
        }

    private suspend inline fun <reified T> get(base: HttpUrl, path: String): T = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, path) ?: throw IOException("URL を組み立てられません: $path")
        client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("${res.code} ${res.message}")
            json.decodeFromString<T>(res.body.string())
        }
    }
}
