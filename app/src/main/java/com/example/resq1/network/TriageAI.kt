package com.example.resq1.network

object TriageAI {

    /**
     * Expanded heuristic engine for disaster triage keywords.
     */
    fun analyzeSeverity(text: String): Int {
        val input = text.lowercase()
        
        val criticalKeywords = listOf(
            "bleeding", "breathe", "choke", "dying", "heart", "unconscious",
            "blood", "chest", "head", "stroke", "seizure", "heavy", "severe"
        )
        
        val urgentKeywords = listOf(
            "broken", "stuck", "pain", "trapped", "arm", "leg", "fracture",
            "bone", "burn", "cut", "wound", "cannot move", "fall", "injury"
        )
        
        val infoKeywords = listOf(
            "help", "lost", "where", "water", "food", "cold", "shelter",
            "family", "fire", "smoke", "flood", "missing", "safe", "okay"
        )

        return when {
            criticalKeywords.any { input.contains(it) } -> 3
            urgentKeywords.any { input.contains(it) } -> 2
            infoKeywords.any { input.contains(it) } -> 1
            else -> 1 
        }
    }

    fun getSeverityLabel(level: Int): String {
        return when (level) {
            3 -> "CRITICAL (Immediate Dispatch)"
            2 -> "URGENT (Medical Support)"
            else -> "STABLE (Info/Relief)"
        }
    }
}
