package com.example.livetranslator

data class Sentence(
    val id: Long,
    var sourceText: String,
    var translatedText: String = "",
    var replyText: String = "",
    var status: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
