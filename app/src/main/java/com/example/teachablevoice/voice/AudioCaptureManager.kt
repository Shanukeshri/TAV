package com.example.teachablevoice.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages raw PCM audio capture using AudioRecord.
 */
class AudioCaptureManager(private val listener: AudioCaptureListener) {

    interface AudioCaptureListener {
        fun onAudioData(data: ByteArray, size: Int)
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("Failed to initialize AudioRecord")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "Started recording audio")

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        listener.onAudioData(buffer, readResult)
                    } else if (readResult < 0) {
                        Log.e(TAG, "AudioRecord read error: $readResult")
                        listener.onError("AudioRecord read error")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
            listener.onError("Error starting AudioRecord: ${e.message}")
        }
    }

    fun stop() {
        if (!isRecording) return
        Log.i(TAG, "Stopping audio capture")
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
        } finally {
            audioRecord = null
        }
    }
}
