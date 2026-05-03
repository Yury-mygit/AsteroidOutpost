package com.example.asteroidoutpost.intelligence

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

class CommandClassifier(context: Context) {

    private val vocab: Map<String, Int>
    private val idf: FloatArray
    private val classes: List<String>
    private val coef: Array<FloatArray>
    private val intercept: FloatArray

    init {
        val json = context.assets.open("ml/model.json").bufferedReader().readText()
        val root = JSONObject(json)

        val vocabObj = root.getJSONObject("vocab")
        vocab = HashMap<String, Int>(vocabObj.length()).also { map ->
            vocabObj.keys().forEach { key -> map[key] = vocabObj.getInt(key) }
        }

        val idfArr = root.getJSONArray("idf")
        idf = FloatArray(idfArr.length()) { idfArr.getDouble(it).toFloat() }

        val classArr = root.getJSONArray("classes")
        classes = List(classArr.length()) { classArr.getString(it) }

        val coefArr = root.getJSONArray("coef")
        coef = Array(coefArr.length()) { i ->
            val row = coefArr.getJSONArray(i)
            FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
        }

        val intArr = root.getJSONArray("intercept")
        intercept = FloatArray(intArr.length()) { intArr.getDouble(it).toFloat() }
    }

    fun classify(text: String): Result {
        val vec = tfidf(text)
        val scores = FloatArray(classes.size) { i ->
            var s = intercept[i]
            for (j in vec.indices) s += coef[i][j] * vec[j]
            s
        }
        val probs = softmax(scores)
        val best = probs.indices.maxBy { probs[it] }
        return Result(classes[best], probs[best], classes.zip(probs.toList()).toMap())
    }

    private fun tfidf(text: String): FloatArray {
        val tf = HashMap<Int, Float>()
        for (word in text.lowercase().split(" ")) {
            val padded = " $word "
            for (n in 2..4) {
                for (start in 0..padded.length - n) {
                    val gram = padded.substring(start, start + n)
                    val idx = vocab[gram] ?: continue
                    tf[idx] = (tf[idx] ?: 0f) + 1f
                }
            }
        }
        val vec = FloatArray(idf.size)
        for ((idx, count) in tf) {
            vec[idx] = (1f + ln(count)) * idf[idx]
        }
        val norm = sqrt(vec.fold(0f) { acc, v -> acc + v * v })
        if (norm > 0f) for (i in vec.indices) vec[i] /= norm
        return vec
    }

    private fun softmax(scores: FloatArray): FloatArray {
        val max = scores.max()
        val exp = FloatArray(scores.size) { Math.exp((scores[it] - max).toDouble()).toFloat() }
        val sum = exp.sum()
        return FloatArray(exp.size) { exp[it] / sum }
    }

    data class Result(
        val label: String,
        val confidence: Float,
        val probs: Map<String, Float>,
    )
}
