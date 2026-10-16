package com.dubdu.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GeminiDubEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun translateAudioChunkToPersian(wavBytes: ByteArray, apiKey: String, provider: Int): String {
        return try {
            if (provider == 0) {
                callGeminiAudioToPersian(wavBytes, apiKey)
            } else {
                callOpenAiWhisperAndTranslate(wavBytes, apiKey)
            }
        } catch (e: Exception) {
            "ERROR: عدم اتصال به سرور (${e.localizedMessage}) - VPN خود را بررسی کنید"
        }
    }

    private fun callGeminiAudioToPersian(wavBytes: ByteArray, apiKey: String): String {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        // Gemini 2.0 Flash was shut down; Gemini 3.6 Flash supports audio input and text output.
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey"

        val prompt = """
            Listen to this audio clip. If someone is speaking, translate what they say into short, natural conversational Persian (Farsi) for live dubbing.
            Output ONLY the Persian translation. If there is no speech, output EMPTY.
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
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return "ERROR: کد ${response.code} - ${bodyStr.take(300)}"
            }
            val json = JSONObject(bodyStr)
            val text = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim() ?: ""

            return if (text.equals("EMPTY", ignoreCase = true)) "" else text
        }
    }

    private fun callOpenAiWhisperAndTranslate(wavBytes: ByteArray, apiKey: String): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", "chunk.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
            .build()

        val sttRequest = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val transcript = client.newCall(sttRequest).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) return "ERROR: Whisper ${resp.code} - ${body.take(100)}"
            JSONObject(body).optString("text", "").trim()
        }

        if (transcript.isBlank()) return ""

        val chatPayload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Translate into natural spoken Persian (Farsi) for dubbing. Output ONLY Persian text."))
                .put(JSONObject().put("role", "user").put("content", transcript))
            )
        }

        val chatReq = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(chatPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(chatReq).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) return "ERROR: GPT ${resp.code}"
            return JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim() ?: ""
        }
    }

    // دریافت فایل صوتی MP3 دوبله فارسی بدون وابستگی به TTS نصب‌شده روی گوشی
    fun fetchPersianTtsAudio(persianText: String): ByteArray? {
        return try {
            val encoded = URLEncoder.encode(persianText.take(200), "UTF-8")
            val ttsUrl = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encoded&tl=fa&client=tw-ob"
            val req = Request.Builder()
                .url(ttsUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
