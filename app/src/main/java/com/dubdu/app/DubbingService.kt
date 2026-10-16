package com.dubdu.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class DubbingService : Service() {

    companion object {
        var isRunning = false
        var projectionResultCode: Int = 0
        var projectionData: Intent? = null
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 5
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var duckAudio = true

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val useMic = intent?.getBooleanExtra("USE_MIC", false) ?: false
        startForegroundProperly(useMic)
        isRunning = true

        val apiKey = intent?.getStringExtra("API_KEY") ?: ""
        val provider = intent?.getIntExtra("PROVIDER", 0) ?: 0
        duckAudio = intent?.getBooleanExtra("DUCK_AUDIO", true) ?: true

        startAudioCaptureLoop(apiKey, provider, useMic)
        return START_NOT_STICKY
    }

    private fun sendLog(msg: String) {
        val intent = Intent("DUBDU_LOG_EVENT").apply {
            setPackage(packageName)
            putExtra("LOG_MSG", msg)
        }
        sendBroadcast(intent)
        updateNotification(msg)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop(apiKey: String, provider: Int, useMic: Boolean) {
        serviceScope.launch {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBuf * 2, SAMPLE_RATE * 2)

                audioRecord = if (!useMic && projectionResultCode == Activity.RESULT_OK && projectionData != null) {
                    sendLog("🎧 راه‌اندازی ضبط صدای داخلی سیستم...")
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(projectionResultCode, projectionData!!)

                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    sendLog("🎙️ راه‌اندازی ضبط از طریق میکروفون...")
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    sendLog("❌ خطا: AudioRecord مقداردهی نشد! حالت میکروفون را امتحان کنید.")
                    return@launch
                }

                audioRecord?.startRecording()
                sendLog("✅ ضبط صدا آغاز شد (برش‌های $CHUNK_SECONDS ثانیه‌ای)...")

                val samplesPerChunk = SAMPLE_RATE * CHUNK_SECONDS
                val chunkBuffer = ShortArray(samplesPerChunk)
                var currentOffset = 0

                while (isActive && isRunning) {
                    val readCount = audioRecord?.read(
                        chunkBuffer,
                        currentOffset,
                        samplesPerChunk - currentOffset
                    ) ?: 0

                    if (readCount > 0) {
                        currentOffset += readCount
                    } else {
                        delay(50)
                    }

                    if (currentOffset >= samplesPerChunk) {
                        val copyChunk = chunkBuffer.copyOf()
                        currentOffset = 0

                        val rms = calculateRms(copyChunk)
                        if (rms < 40) {
                            sendLog("🔇 سکوت تشخیص داده شد (RMS=${rms.toInt()}). اگر ویدئو پخش می‌شود، آن برنامه صدای داخلی را قفل کرده؛ تیک میکروفون را بزنید.")
                        } else {
                            sendLog("📤 ارسال قطعه صوتی (بلندی صدا: ${rms.toInt()}) برای ترجمه...")
                            val wavBytes = pcmToWav(copyChunk, SAMPLE_RATE)
                            launch {
                                val result = GeminiDubEngine.translateAudioChunkToPersian(
                                    wavBytes = wavBytes,
                                    apiKey = apiKey,
                                    provider = provider
                                )
                                if (result.startsWith("ERROR:")) {
                                    sendLog("❌ خطای API: ${result.removePrefix("ERROR:")}")
                                } else if (result.isNotBlank()) {
                                    sendLog("🗣️ ترجمه: $result")
                                    playPersianCloudTts(result)
                                } else {
                                    sendLog("⚪ گفتاری در این ۵ ثانیه نبود.")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                sendLog("❌ خطای سرویس: ${e.message}")
            }
        }
    }

    private fun playPersianCloudTts(persianText: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // دانلود صوت دوبله فارسی و پخش فوری روی استریم مدیا
                val audioBytes = GeminiDubEngine.fetchPersianTtsAudio(persianText)
                if (audioBytes == null || audioBytes.isEmpty()) {
                    sendLog("⚠️ خطا در دریافت صدای گوینده فارسی")
                    return@launch
                }

                val tempMp3 = File(cacheDir, "dub_chunk_${System.currentTimeMillis()}.mp3")
                FileOutputStream(tempMp3).use { it.write(audioBytes) }

                withContext(Dispatchers.Main) {
                    var focusReq: AudioFocusRequest? = null
                    if (duckAudio) {
                        focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            ).build()
                        audioManager.requestAudioFocus(focusReq)
                    }

                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempMp3.absolutePath)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        // افزایش ملایم سرعت پخش دوبله فارسی (1.2x) برای جا نماندن از ویدئو
                        prepare()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            playbackParams = playbackParams.setSpeed(1.2f)
                        }
                        setOnCompletionListener {
                            focusReq?.let { req -> audioManager.abandonAudioFocusRequest(req) }
                            tempMp3.delete()
                        }
                        start()
                    }
                }
            } catch (e: Exception) {
                sendLog("❌ خطای پخش صدا: ${e.message}")
            }
        }
    }

    private fun calculateRms(buffer: ShortArray): Double {
        var sum = 0.0
        for (s in buffer) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / buffer.size)
    }

    private fun pcmToWav(pcmData: ShortArray, sampleRate: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcmData) byteBuffer.putShort(s)
        val rawBytes = byteBuffer.array()

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(rawBytes.size + 36)
        header.put("WAVEfmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(rawBytes.size)

        out.write(header.array())
        out.write(rawBytes)
        return out.toByteArray()
    }

    private fun startForegroundProperly(useMic: Boolean) {
        val channelId = "dubdu_live_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Dubdu Live", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val notification = buildNotification("در حال آماده‌سازی دوبله زنده...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (useMic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else 0
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (type != 0) {
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, "dubdu_live_channel")
            .setContentTitle("Dubdu دوبله زنده فارسی")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        mediaProjection?.stop()
        mediaPlayer?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
