package com.example.livetranslator

import java.io.File

data class RecordingItem(
    val file: File,
    val name: String,
    val timestamp: Long,
    val sizeBytes: Long
) {
    var transcription: String = ""
    var translation: String   = ""
    var isPlaying: Boolean    = false
}
