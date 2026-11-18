package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.BotConfig
import com.example.model.FormulaMode
import com.example.model.JumpOutcome
import com.example.model.JumpTelemetryRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.botDataStore: DataStore<Preferences> by preferencesDataStore(name = "jump_assist_prefs")

class BotPreferencesRepository(private val context: Context) {

    private object Keys {
        val FORMULA_MODE = stringPreferencesKey("formula_mode")
        val K_FACTOR = floatPreferencesKey("k_factor")
        val OFFSET_MS = floatPreferencesKey("offset_ms")
        val QUAD_FACTOR = floatPreferencesKey("quad_factor")
        val PAWN_COLOR_RGB = intPreferencesKey("pawn_color_rgb")
        val PAWN_COLOR_TOLERANCE = intPreferencesKey("pawn_color_tolerance")
        val BG_DIFF_THRESHOLD = intPreferencesKey("bg_diff_threshold")
        val ROI_TOP_FRACTION = floatPreferencesKey("roi_top_fraction")
        val ROI_BOTTOM_FRACTION = floatPreferencesKey("roi_bottom_fraction")
        val TAP_X_FRACTION = floatPreferencesKey("tap_x_fraction")
        val TAP_Y_FRACTION = floatPreferencesKey("tap_y_fraction")
        val JUMP_COOLDOWN_MS = longPreferencesKey("jump_cooldown_ms")
        val MIN_PRESS_MS = longPreferencesKey("min_press_ms")
        val MAX_PRESS_MS = longPreferencesKey("max_press_ms")
        val AUTO_TRAIN_ENABLED = booleanPreferencesKey("auto_train_enabled")
        val HUMANIZE_TIMING = booleanPreferencesKey("humanize_timing")
        val ADAPTIVE_BIASES_JSON = stringPreferencesKey("adaptive_biases_json")
        val TRAINING_HISTORY_JSON = stringPreferencesKey("training_history_json")
        val CALIB_VERSION = intPreferencesKey("calib_version")
    }

    private companion object {
        const val MIN_VALID_CALIB_VERSION = 7
        const val CURRENT_CALIB_VERSION = 13
        const val MAX_STORED_RECORDS = 60
    }

    val configFlow: Flow<BotConfig> = context.botDataStore.data.map { prefs ->
        readConfigFromPrefs(prefs)
    }

    val trainingHistoryFlow: Flow<List<JumpTelemetryRecord>> = context.botDataStore.data.map { prefs ->
        val raw = prefs[Keys.TRAINING_HISTORY_JSON] ?: return@map emptyList()
        decodeHistory(raw)
    }

    private fun readConfigFromPrefs(prefs: Preferences): BotConfig {
        val storedVersion = prefs[Keys.CALIB_VERSION] ?: 1
        val defaults = BotConfig()
        val seededNearBiases = mapOf("DIST:NEAR200" to -30f, "DIST:NEAR270" to -14f)
        if (storedVersion < MIN_VALID_CALIB_VERSION) {
            return defaults.copy(adaptiveBiasesMs = seededNearBiases)
        }
        val modeName = prefs[Keys.FORMULA_MODE] ?: FormulaMode.LINEAR.name
        val mode = runCatching { FormulaMode.valueOf(modeName) }.getOrDefault(FormulaMode.LINEAR)
        val rawCooldown = prefs[Keys.JUMP_COOLDOWN_MS] ?: defaults.jumpCooldownMs
        val migratedCooldown = if (storedVersion < CURRENT_CALIB_VERSION && rawCooldown >= 1500L) {
            defaults.jumpCooldownMs
        } else {
            rawCooldown
        }
        val rawBiases = decodeBiases(prefs[Keys.ADAPTIVE_BIASES_JSON])
        val biases = LinkedHashMap<String, Float>(rawBiases.size)
        rawBiases.forEach { (k, v) ->
            if (k != "BLK:MEDIUM") {
                biases[k] = when {
                    storedVersion < CURRENT_CALIB_VERSION && k == "BLK:MINI" -> v.coerceIn(-4f, 6f)
                    storedVersion < CURRENT_CALIB_VERSION && k == "BLK:NARROW" -> v.coerceIn(-6f, 4f)
                    storedVersion < CURRENT_CALIB_VERSION && k == "DIST:NEAR270" -> v.coerceIn(-20f, -8f)
                    storedVersion < CURRENT_CALIB_VERSION && k == "DIST:NEAR200" -> v.coerceIn(-38f, -24f)
                    storedVersion < CURRENT_CALIB_VERSION && k.startsWith("MIST:") -> v.coerceIn(-10f, 10f)
                    storedVersion < CURRENT_CALIB_VERSION && k == "STANCE:SIDE_BACK" -> v.coerceIn(-4f, 6f)
                    storedVersion < CURRENT_CALIB_VERSION && k.startsWith("STANCE:") -> v.coerceIn(-10f, 6f)
                    k == "DIST:NEAR270" -> v.coerceIn(-26f, 10f)
                    k == "DIST:NEAR200" -> v.coerceIn(-42f, 8f)
                    k.startsWith("SIG:MEDIUM_") -> v.coerceIn(-12f, 12f)
                    k.startsWith("SIG:") -> v.coerceIn(-16f, 16f)
                    k.startsWith("STANCE:") -> v.coerceIn(-14f, 14f)
                    k.startsWith("MIST:") -> v.coerceIn(-38f, 38f)
                    else -> v
                }
            }
        }
        if (storedVersion < CURRENT_CALIB_VERSION || !prefs.contains(Keys.ADAPTIVE_BIASES_JSON)) {
            if (storedVersion < CURRENT_CALIB_VERSION) {
                biases["DIST:NEAR200"] = (biases["DIST:NEAR200"] ?: -30f).coerceIn(-38f, -24f)
                biases["DIST:NEAR270"] = (biases["DIST:NEAR270"] ?: -14f).coerceIn(-20f, -8f)
            } else {
                biases.putIfAbsent("DIST:NEAR200", -30f)
                biases.putIfAbsent("DIST:NEAR270", -14f)
            }
        }
        val rawK = prefs[Keys.K_FACTOR] ?: defaults.kFactor
        val rawB = prefs[Keys.OFFSET_MS] ?: defaults.offsetMs
        val migratedK = if (storedVersion < CURRENT_CALIB_VERSION) {
            rawK.coerceIn(1.370f, 1.378f)
        } else {
            rawK
        }
        val migratedB = if (storedVersion < CURRENT_CALIB_VERSION) {
            rawB.coerceIn(28f, 33f)
        } else {
            rawB
        }
        return BotConfig(
            formulaMode = mode,
            kFactor = migratedK,
            offsetMs = migratedB,
            quadFactor = prefs[Keys.QUAD_FACTOR] ?: defaults.quadFactor,
            pawnColorRgb = prefs[Keys.PAWN_COLOR_RGB] ?: defaults.pawnColorRgb,
            pawnColorTolerance = prefs[Keys.PAWN_COLOR_TOLERANCE] ?: defaults.pawnColorTolerance,
            bgDiffThreshold = prefs[Keys.BG_DIFF_THRESHOLD] ?: defaults.bgDiffThreshold,
            roiTopFraction = prefs[Keys.ROI_TOP_FRACTION] ?: defaults.roiTopFraction,
            roiBottomFraction = prefs[Keys.ROI_BOTTOM_FRACTION] ?: defaults.roiBottomFraction,
            tapXFraction = prefs[Keys.TAP_X_FRACTION] ?: defaults.tapXFraction,
            tapYFraction = prefs[Keys.TAP_Y_FRACTION] ?: defaults.tapYFraction,
            jumpCooldownMs = migratedCooldown,
            minPressMs = prefs[Keys.MIN_PRESS_MS] ?: defaults.minPressMs,
            maxPressMs = prefs[Keys.MAX_PRESS_MS] ?: defaults.maxPressMs,
            autoTrainEnabled = prefs[Keys.AUTO_TRAIN_ENABLED] ?: defaults.autoTrainEnabled,
            humanizeTiming = prefs[Keys.HUMANIZE_TIMING] ?: defaults.humanizeTiming,
            adaptiveBiasesMs = biases
        )
    }

    suspend fun updateConfig(transform: (BotConfig) -> BotConfig) {
        context.botDataStore.edit { prefs ->
            val current = readConfigFromPrefs(prefs)
            val updated = transform(current)
            prefs[Keys.CALIB_VERSION] = CURRENT_CALIB_VERSION
            prefs[Keys.FORMULA_MODE] = updated.formulaMode.name
            prefs[Keys.K_FACTOR] = updated.kFactor
            prefs[Keys.OFFSET_MS] = updated.offsetMs
            prefs[Keys.QUAD_FACTOR] = updated.quadFactor
            prefs[Keys.PAWN_COLOR_RGB] = updated.pawnColorRgb
            prefs[Keys.PAWN_COLOR_TOLERANCE] = updated.pawnColorTolerance
            prefs[Keys.BG_DIFF_THRESHOLD] = updated.bgDiffThreshold
            prefs[Keys.ROI_TOP_FRACTION] = updated.roiTopFraction
            prefs[Keys.ROI_BOTTOM_FRACTION] = updated.roiBottomFraction
            prefs[Keys.TAP_X_FRACTION] = updated.tapXFraction
            prefs[Keys.TAP_Y_FRACTION] = updated.tapYFraction
            prefs[Keys.JUMP_COOLDOWN_MS] = updated.jumpCooldownMs
            prefs[Keys.MIN_PRESS_MS] = updated.minPressMs
            prefs[Keys.MAX_PRESS_MS] = updated.maxPressMs
            prefs[Keys.AUTO_TRAIN_ENABLED] = updated.autoTrainEnabled
            prefs[Keys.HUMANIZE_TIMING] = updated.humanizeTiming
            prefs[Keys.ADAPTIVE_BIASES_JSON] = encodeBiases(updated.adaptiveBiasesMs)
        }
    }

    suspend fun saveTrainingHistory(records: List<JumpTelemetryRecord>) {
        val trimmed = records.take(MAX_STORED_RECORDS)
        val encoded = encodeHistory(trimmed)
        context.botDataStore.edit { prefs ->
            prefs[Keys.CALIB_VERSION] = CURRENT_CALIB_VERSION
            prefs[Keys.TRAINING_HISTORY_JSON] = encoded
        }
    }

    suspend fun clearTrainingHistory() {
        context.botDataStore.edit { prefs ->
            prefs.remove(Keys.TRAINING_HISTORY_JSON)
        }
    }

    suspend fun resetDefaults() {
        context.botDataStore.edit { prefs ->
            val historyJson = prefs[Keys.TRAINING_HISTORY_JSON]
            prefs.clear()
            prefs[Keys.CALIB_VERSION] = CURRENT_CALIB_VERSION
            if (historyJson != null) {
                prefs[Keys.TRAINING_HISTORY_JSON] = historyJson
            }
        }
    }

    private fun encodeBiases(biases: Map<String, Float>): String {
        val obj = JSONObject()
        biases.forEach { (key, value) ->
            obj.put(key, value.toDouble())
        }
        return obj.toString()
    }

    private fun decodeBiases(raw: String?): Map<String, Float> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val result = LinkedHashMap<String, Float>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val v = obj.optDouble(key, 0.0).toFloat()
                if (v.isFinite()) {
                    result[key] = v
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun encodeHistory(records: List<JumpTelemetryRecord>): String {
        val arr = JSONArray()
        records.forEach { r ->
            val obj = JSONObject().apply {
                put("id", r.id)
                put("ts", r.timestamp)
                put("px", r.pawnX.toDouble())
                put("py", r.pawnY.toDouble())
                put("pp", r.pawnPixels)
                put("bx", r.blockX.toDouble())
                put("by", r.blockY.toDouble())
                put("bw", r.blockWidth.toDouble())
                put("bh", r.blockHeight.toDouble())
                put("dr", r.rawDistancePx.toDouble())
                put("dn", r.normalizedDistancePx.toDouble())
                put("hm", r.holdMs)
                if (r.landingRatio != null) put("lr", r.landingRatio.toDouble())
                put("out", r.outcome.name)
                put("kb", r.kBefore.toDouble())
                put("bb", r.bBefore.toDouble())
                put("ka", r.kAfter.toDouble())
                put("ba", r.bAfter.toDouble())
                put("st", r.skyTopHex)
                put("sm", r.skyMidHex)
                put("ps", r.pawnSampleHex)
                put("bt", r.blockTopHex)
                put("bs", r.blockSideHex)
                put("nt", r.note)
                put("pst", r.pawnStance)
                put("sof", r.stanceOffsetFrac.toDouble())
                put("sw", r.sourceWidthPx.toDouble())
                put("tp", r.targetProfile)
                put("tc", r.targetColorGroup)
                put("ab", r.appliedBiasMs)
                if (r.scoreDelta != null) put("sd", r.scoreDelta)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeHistory(raw: String): List<JumpTelemetryRecord> {
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<JumpTelemetryRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val outName = o.optString("out", JumpOutcome.UNKNOWN.name)
                val outcome = runCatching { JumpOutcome.valueOf(outName) }.getOrDefault(JumpOutcome.UNKNOWN)
                list.add(
                    JumpTelemetryRecord(
                        id = o.optLong("id", 0L),
                        timestamp = o.optString("ts", ""),
                        pawnX = o.optDouble("px", 0.0).toFloat(),
                        pawnY = o.optDouble("py", 0.0).toFloat(),
                        pawnPixels = o.optInt("pp", 0),
                        blockX = o.optDouble("bx", 0.0).toFloat(),
                        blockY = o.optDouble("by", 0.0).toFloat(),
                        blockWidth = o.optDouble("bw", 0.0).toFloat(),
                        blockHeight = o.optDouble("bh", 0.0).toFloat(),
                        rawDistancePx = o.optDouble("dr", 0.0).toFloat(),
                        normalizedDistancePx = o.optDouble("dn", 0.0).toFloat(),
                        holdMs = o.optLong("hm", 0L),
                        landingRatio = if (o.has("lr")) o.optDouble("lr").toFloat() else null,
                        outcome = outcome,
                        kBefore = o.optDouble("kb", 1.26).toFloat(),
                        bBefore = o.optDouble("bb", 38.0).toFloat(),
                        kAfter = o.optDouble("ka", 1.26).toFloat(),
                        bAfter = o.optDouble("ba", 38.0).toFloat(),
                        skyTopHex = o.optString("st", ""),
                        skyMidHex = o.optString("sm", ""),
                        pawnSampleHex = o.optString("ps", ""),
                        blockTopHex = o.optString("bt", ""),
                        blockSideHex = o.optString("bs", ""),
                        note = o.optString("nt", ""),
                        pawnStance = o.optString("pst", "CENTER"),
                        stanceOffsetFrac = o.optDouble("sof", 0.0).toFloat(),
                        sourceWidthPx = o.optDouble("sw", 0.0).toFloat(),
                        targetProfile = o.optString("tp", "MEDIUM"),
                        targetColorGroup = o.optString("tc", ""),
                        appliedBiasMs = o.optLong("ab", 0L),
                        scoreDelta = if (o.has("sd")) o.optInt("sd") else null
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }
}
