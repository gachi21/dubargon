package com.dubdu.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.sqrt

class DubbingService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var isRunning = false
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 5 // برش‌های ۵ ثانیه‌ای برای کمترین تاخیر
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private lateinit var audioManager: AudioManager
    private var duckAudio = true

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // تنظیم زبان پیش‌فرض دوبله روی فارسی (fa-IR)
            val result = tts?.setLanguage(Locale("fa", "IR"))
            tts?.setSpeechRate(1.15f) // سرعت کمی بالاتر برای هماهنگی با گوینده اصلی
            setupTtsAudioDucking()
        }
    }

    private fun setupTtsAudioDucking() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (duckAudio) {
                    // کاهش موقت صدای ویدئوی اصلی (Audio Ducking) هنگام صحبت دوبلور فارسی
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        ).build()
                    audioManager.requestAudioFocus(req)
                }
            }
            override fun onDone(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        isRunning = true

        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("DATA")
        val apiKey = intent?.getStringExtra("API_KEY") ?: ""
        val provider = intent?.getIntExtra("PROVIDER", 0) ?: 0
        val useMic = intent?.getBooleanExtra("USE_MIC", false) ?: false
        duckAudio = intent?.getBooleanExtra("DUCK_AUDIO", true) ?: true

        startAudioCaptureLoop(resultCode, data, apiKey, provider, useMic)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop(
        resultCode: Int,
        data: Intent?,
        apiKey: String,
        provider: Int,
        useMic: Boolean
    ) {
        serviceScope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            audioRecord = if (!useMic && resultCode == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)

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
                // حالت جایگزین میکروفون برای اپ‌های مسدودشده مثل یوتیوب
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            audioRecord?.startRecording()

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
                }

                if (currentOffset >= samplesPerChunk) {
                    val copyChunk = chunkBuffer.copyOf()
                    currentOffset = 0

                    // بررسی سکوت (VAD ساده مشابه pcm-capture-worklet مخزن)
                    if (!isSilent(copyChunk)) {
                        val wavBytes = pcmToWav(copyChunk, SAMPLE_RATE)
                        launch {
                            val persianText = GeminiDubEngine.translateAudioChunkToPersian(
                                wavBytes = wavBytes,
                                apiKey = apiKey,
                                provider = provider
                            )
                            if (persianText.isNotBlank()) {
                                speakPersian(persianText)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isSilent(buffer: ShortArray): Boolean {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        val rms = sqrt(sum / buffer.size)
        return rms < 350 // آستانه تشخیص سکوت
    }

    private fun speakPersian(text: String) {
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, UUID.randomUUID().toString())
    }

    private fun pcmToWav(pcmData: ShortArray, sampleRate: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcmData) byteBuffer.putShort(s)
        val rawBytes = byteBuffer.array()

        val out = ByteArrayOutputStream()
        val totalDataLen = rawBytes.size + 36
        val byteRate = sampleRate * 2

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVEfmt ".toByteArray())
        header.putInt(16) // Subchunk1Size
        header.putShort(1) // PCM
        header.putShort(1) // Mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray())
        header.putInt(rawBytes.size)

        out.write(header.array())
        out.write(rawBytes)
        return out.toByteArray()
    }

    private fun startForegroundWithNotification() {
        val channelId = "dubdu_live_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "سرویس دوبله زنده Dubdu",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dubdu در حال دوبله زنده به فارسی")
            .setContentText("در حال گوش دادن به صدای ویدئو و ترجمه همزمان...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
