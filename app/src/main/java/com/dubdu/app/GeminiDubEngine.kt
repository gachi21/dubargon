package com.dubdu.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiDubEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun translateAudioChunkToPersian(wavBytes: ByteArray, apiKey: String, provider: Int): String {
        return try {
            if (provider == 0) {
                callGeminiAudioToPersian(wavBytes, apiKey)
            } else {
                callOpenAiWhisperAndTranslate(wavBytes, apiKey)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun callGeminiAudioToPersian(wavBytes: ByteArray, apiKey: String): String {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

        val prompt = """
            Listen to this audio clip. If there is speech, translate it directly into natural, conversational Persian (Farsi) suitable for live voice-over dubbing. 
            Return ONLY the Persian translated sentence without any extra explanation, quotes, or markdown. If there is no clear speech or only music/noise, return nothing.
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", base64Audio)
                    ))
                )
            ))
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            val bodyStr = response.body?.string() ?: return ""
            val json = JSONObject(bodyStr)
            return json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim() ?: ""
        }
    }

    private fun callOpenAiWhisperAndTranslate(wavBytes: ByteArray, apiKey: String): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart(
                "file",
                "chunk.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val sttRequest = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val transcript = client.newCall(sttRequest).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            JSONObject(resp.body?.string() ?: "{}").optString("text", "").trim()
        }

        if (transcript.isBlank()) return ""

        // ترجمه متن به فارسی محاوره‌ای برای دوبله
        val chatPayload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Translate the user text into natural Persian for live video dubbing. Output ONLY the Persian translation."))
                .put(JSONObject().put("role", "user").put("content", transcript))
            )
        }

        val chatReq = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(chatPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(chatReq).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val json = JSONObject(resp.body?.string() ?: "{}")
            return json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim() ?: ""
        }
    }
}
