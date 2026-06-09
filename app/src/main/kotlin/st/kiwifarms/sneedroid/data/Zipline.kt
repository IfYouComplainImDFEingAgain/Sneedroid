package st.kiwifarms.sneedroid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Uploads images to a self-hosted Zipline instance (POST {url}/api/upload). */
object Zipline {
    // Honour the IP killswitch: don't upload (revealing your IP to the host) without a VPN.
    private val client = OkHttpClient.Builder().addInterceptor(KillswitchInterceptor()).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the hosted image URL on success, or an error message on failure. */
    suspend fun upload(
        baseUrl: String,
        apiKey: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
                )
                .build()
            val url = baseUrl.trimEnd('/') + "/api/upload"
            val request = Request.Builder().url(url).header("Authorization", apiKey).post(body).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(140)}")
                // Zipline returns { "files": [ { "url": "..." } ] } (v3/v4); also tolerate [ "url" ].
                val files = json.parseToJsonElement(text).jsonObject["files"]?.jsonArray
                    ?: error("no files in response")
                val first = files.firstOrNull() ?: error("empty files array")
                val urlStr = runCatching { first.jsonObject["url"]?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { first.jsonPrimitive.content }.getOrNull()
                    ?: error("no url in response")
                urlStr
            }
        }
    }
}
