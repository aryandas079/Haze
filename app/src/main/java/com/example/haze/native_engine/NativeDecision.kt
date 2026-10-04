package com.example.haze.native_engine

data class NativeDecision(
    val shouldBlock: Boolean,
    val category: String,
    val reason: String,
    val matchedRule: String,
    val bytesSaved: Long,
    val timeSavedMs: Long
)
