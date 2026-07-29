package com.frameworkstudios.autotapper

import org.json.JSONArray
import org.json.JSONObject

/**
 * One action in a sequence. A profile is an ordered list of steps that the
 * loop engine executes top to bottom, then repeats.
 */
sealed class Step {

    data class Tap(
        val x: Float, val y: Float,
        val radius: Int, val count: Int, val gapMs: Long
    ) : Step()

    data class Swipe(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val durationMs: Long, val jitterPx: Int
    ) : Step()

    data class Wait(val ms: Long) : Step()

    fun label(index: Int): String = when (this) {
        is Tap -> "${index + 1}.  Tap  (${x.toInt()}, ${y.toInt()})" +
            (if (count > 1) "  ×$count" else "")
        is Swipe -> "${index + 1}.  Swipe  (${x1.toInt()},${y1.toInt()}) → (${x2.toInt()},${y2.toInt()})"
        is Wait -> "${index + 1}.  Wait  $ms ms"
    }

    fun toJson(): JSONObject = when (this) {
        is Tap -> JSONObject()
            .put("t", "tap")
            .put("x", x.toDouble()).put("y", y.toDouble())
            .put("r", radius).put("c", count).put("g", gapMs)
        is Swipe -> JSONObject()
            .put("t", "swipe")
            .put("x1", x1.toDouble()).put("y1", y1.toDouble())
            .put("x2", x2.toDouble()).put("y2", y2.toDouble())
            .put("d", durationMs).put("j", jitterPx)
        is Wait -> JSONObject().put("t", "wait").put("ms", ms)
    }

    companion object {
        fun fromJson(o: JSONObject): Step? = when (o.optString("t")) {
            "tap" -> Tap(
                o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                o.optInt("r", 0), o.optInt("c", 1).coerceIn(1, 10),
                o.optLong("g", 150).coerceIn(50, 2000)
            )
            "swipe" -> Swipe(
                o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(),
                o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(),
                o.optLong("d", 300).coerceIn(50, 5000), o.optInt("j", 0)
            )
            "wait" -> Wait(o.optLong("ms", 1000).coerceIn(50, 60000))
            else -> null
        }

        fun listToJson(steps: List<Step>): JSONArray {
            val arr = JSONArray()
            steps.forEach { arr.put(it.toJson()) }
            return arr
        }

        fun listFromJson(arr: JSONArray?): MutableList<Step> {
            val out = mutableListOf<Step>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> fromJson(o)?.let { out.add(it) } }
            }
            return out
        }
    }
}
