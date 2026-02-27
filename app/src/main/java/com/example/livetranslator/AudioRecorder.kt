package com.example.livetranslator

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wraps MediaRecorder to save audio files into the app's private files dir.
 * No extra storage permissions required (API 26+).
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** Start recording. Returns the destination File, or null on failure. */
    fun start(): File? {
        stop()                                   // safety: stop any in-progress
        val dir = recordingsDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val file = File(dir, "REC_$ts.m4a")
        currentFile = file

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(128_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
        } catch (e: Exception) {
            mr.release()
            file.delete()
            currentFile = null
            return null
        }
        return file
    }

    /**
     * Stop recording. Returns the saved File (or null if nothing was recorded /
     * the file is empty).
     */
    fun stop(): File? {
        val mr = recorder ?: return null
        recorder = null
        return try {
            mr.stop()
            mr.release()
            val f = currentFile
            currentFile = null
            if (f != null && f.exists() && f.length() > 0) f else null
        } catch (e: Exception) {
            mr.release()
            currentFile?.delete()
            currentFile = null
            null
        }
    }

    companion object {
        fun recordingsDir(context: Context): File {
            val dir = File(context.filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun listAll(context: Context): List<RecordingItem> {
            val dir = recordingsDir(context)
            return dir.listFiles { f -> f.extension == "m4a" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    RecordingItem(
                        file      = f,
                        name      = f.nameWithoutExtension,
                        timestamp = f.lastModified(),
                        sizeBytes = f.length()
                    )
                } ?: emptyList()
        }
    }
}
