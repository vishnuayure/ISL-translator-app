package com.example.a1

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.sqrt

// ===== DATA CLASSES =====

data class Point(val x: Float, val y: Float, val z: Float = 0f)

data class HandLandmarks(
    val leftHand: List<Point>?,  // 21 points or null if not detected
    val rightHand: List<Point>?  // 21 points or null if not detected
)

data class GesturePattern(
    val name: String,
    val samples: List<HandLandmarks>,
    val timestamp: Long = System.currentTimeMillis()
)

// ===== GESTURE STORAGE MANAGER =====

object GestureStorage {
    private val gestures = mutableMapOf<String, GesturePattern>()
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences
    private const val PREFS_NAME = "ISL_GESTURES"
    private const val KEY_GESTURES = "gestures"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadGestures()
    }

    private fun loadGestures() {
        try {
            val json = prefs.getString(KEY_GESTURES, null)
            if (json != null) {
                val type = object : TypeToken<Map<String, GesturePattern>>() {}.type
                val loadedGestures: Map<String, GesturePattern> = gson.fromJson(json, type)
                gestures.clear()
                gestures.putAll(loadedGestures)
                println("✅ Loaded ${gestures.size} gestures from storage")
            }
        } catch (e: Exception) {
            println("❌ Failed to load gestures: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun saveToPrefs() {
        try {
            val json = gson.toJson(gestures)
            prefs.edit().putString(KEY_GESTURES, json).apply()
            println("💾 Saved ${gestures.size} gestures to storage")
        } catch (e: Exception) {
            println("❌ Failed to save gestures: ${e.message}")
            e.printStackTrace()
        }
    }

    fun saveGesture(gesture: GesturePattern) {
        gestures[gesture.name] = gesture
        saveToPrefs()
        println("✅ Gesture '${gesture.name}' saved with ${gesture.samples.size} samples")
    }

    fun getAllGestures(): List<GesturePattern> = gestures.values.toList()

    fun deleteGesture(name: String) {
        gestures.remove(name)
        saveToPrefs()
        println("🗑️ Gesture '$name' deleted")
    }

    fun recognizeGesture(current: HandLandmarks): Pair<String, Float>? {
        if (gestures.isEmpty()) {
            println("⚠️ No gestures stored to compare against")
            return null
        }

        var bestMatch: String? = null
        var bestSimilarity = 0f

        gestures.forEach { (name, pattern) ->
            val avgSimilarity = pattern.samples.map { sample ->
                calculateSimilarity(current, sample)
            }.average().toFloat()

            println("🔍 Comparing with '$name': ${(avgSimilarity * 100).toInt()}% similar")

            if (avgSimilarity > bestSimilarity && avgSimilarity > 0.7f) {
                bestSimilarity = avgSimilarity
                bestMatch = name
            }
        }

        return bestMatch?.let {
            println("✅ Best match: $it with ${(bestSimilarity * 100).toInt()}% confidence")
            it to (bestSimilarity * 100)
        }
    }

    private fun calculateSimilarity(current: HandLandmarks, sample: HandLandmarks): Float {
        var totalDistance = 0f
        var pointCount = 0

        // Compare left hands
        if (current.leftHand != null && sample.leftHand != null) {
            current.leftHand.zip(sample.leftHand).forEach { (p1, p2) ->
                totalDistance += distance(p1, p2)
                pointCount++
            }
        }

        // Compare right hands
        if (current.rightHand != null && sample.rightHand != null) {
            current.rightHand.zip(sample.rightHand).forEach { (p1, p2) ->
                totalDistance += distance(p1, p2)
                pointCount++
            }
        }

        if (pointCount == 0) return 0f

        val avgDistance = totalDistance / pointCount
        return 1f / (1f + avgDistance) // Convert distance to similarity (0-1)
    }

    private fun distance(p1: Point, p2: Point): Float {
        return sqrt(
            (p1.x - p2.x) * (p1.x - p2.x) +
                    (p1.y - p2.y) * (p1.y - p2.y) +
                    (p1.z - p2.z) * (p1.z - p2.z)
        )
    }
}