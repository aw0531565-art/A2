package com.example.vision

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight pixel-template / structural digit reader for the game's top-right golden score counter.
 * Used during self-training to distinguish +1 (off-center survival) from +2/+4/+6/... (dead-center combo).
 */
object ScoreDigitReader {

    private data class DigitComponent(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val count: Int
    ) {
        val width: Int get() = (maxX - minX + 1).coerceAtLeast(1)
        val height: Int get() = (maxY - minY + 1).coerceAtLeast(1)
        val midY: Float get() = (minY + maxY) * 0.5f
    }

    private fun isScoreDigitPixel(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        // Top-right score digits have a vertical cream-yellow (#F7EED1) to deep gold (#FCE16C) gradient.
        return r >= 205 && g >= 175 && r >= g - 2 && (r - b) >= 28 && (g - b) >= 20
    }

    fun readScore(bitmap: Bitmap, excludedRect: Rect? = null): Int? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 120 || h < 200) return null

        val x0 = (w * 0.50f).roundToInt().coerceIn(0, w - 1)
        val x1 = (w * 0.96f).roundToInt().coerceIn(x0 + 1, w - 1)
        val y0 = (h * 0.090f).roundToInt().coerceIn(0, h - 1)
        val y1 = (h * 0.220f).roundToInt().coerceIn(y0 + 1, h - 1)

        val scanW = x1 - x0 + 1
        val scanH = y1 - y0 + 1
        val buf = IntArray(scanW * scanH)
        bitmap.getPixels(buf, 0, scanW, x0, y0, scanW, scanH)

        val mask = BooleanArray(scanW * scanH)
        for (ry in 0 until scanH) {
            val absY = y0 + ry
            val rowOff = ry * scanW
            for (rx in 0 until scanW) {
                val absX = x0 + rx
                if (excludedRect != null &&
                    absX in (excludedRect.left - 4)..(excludedRect.right + 4) &&
                    absY in (excludedRect.top - 4)..(excludedRect.bottom + 4)
                ) {
                    continue
                }
                if (isScoreDigitPixel(buf[rowOff + rx])) {
                    mask[rowOff + rx] = true
                }
            }
        }

        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val minDigitH = (h * 0.032f).roundToInt().coerceAtLeast(18)
        val maxDigitH = (h * 0.095f).roundToInt()
        val minDigitW = (w * 0.015f).roundToInt().coerceAtLeast(6)
        val maxDigitW = (w * 0.125f).roundToInt()

        val components = ArrayList<DigitComponent>(8)

        for (idx in mask.indices) {
            if (!mask[idx] || visited[idx]) continue
            var head = 0
            var tail = 0
            queue[tail++] = idx
            visited[idx] = true

            var minRx = Int.MAX_VALUE
            var maxRx = Int.MIN_VALUE
            var minRy = Int.MAX_VALUE
            var maxRy = Int.MIN_VALUE
            var count = 0

            while (head < tail) {
                val cur = queue[head++]
                val ry = cur / scanW
                val rx = cur - ry * scanW
                if (rx < minRx) minRx = rx
                if (rx > maxRx) maxRx = rx
                if (ry < minRy) minRy = ry
                if (ry > maxRy) maxRy = ry
                count++

                for (dy in -1..1) {
                    val ny = ry + dy
                    if (ny !in 0 until scanH) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = rx + dx
                        if (nx !in 0 until scanW) continue
                        val nIdx = ny * scanW + nx
                        if (mask[nIdx] && !visited[nIdx]) {
                            visited[nIdx] = true
                            queue[tail++] = nIdx
                        }
                    }
                }
            }

            val cw = maxRx - minRx + 1
            val ch = maxRy - minRy + 1
            if (ch in minDigitH..maxDigitH && cw in minDigitW..maxDigitW && count >= minDigitH * 3) {
                components.add(
                    DigitComponent(
                        minX = minRx,
                        maxX = maxRx,
                        minY = minRy,
                        maxY = maxRy,
                        count = count
                    )
                )
            }
        }

        if (components.isEmpty()) return null

        // Group digits aligned along the largest shared horizontal score baseline
        var bestRow: List<DigitComponent> = emptyList()
        var bestRowWeight = -1
        for (anchor in components) {
            val row = components.filter {
                abs(it.midY - anchor.midY) <= max(10f, anchor.height * 0.24f) &&
                    abs(it.height - anchor.height) <= anchor.height * 0.28f
            }.sortedBy { it.minX }
            val weight = row.sumOf { it.count }
            if (weight > bestRowWeight) {
                bestRowWeight = weight
                bestRow = row
            }
        }
        if (bestRow.isEmpty() || bestRow.size > 5) return null

        val digits = StringBuilder(bestRow.size)
        for (comp in bestRow) {
            val d = classifyDigit(comp, mask, scanW) ?: return null
            digits.append(d)
        }
        return digits.toString().toIntOrNull()
    }

    private fun classifyDigit(comp: DigitComponent, mask: BooleanArray, scanW: Int): Int? {
        val dw = comp.width
        val dh = comp.height
        val aspect = dw.toFloat() / dh.toFloat()

        // Resample component bounding box onto a normalized 5x7 density grid [0..1]
        val grid = FloatArray(35)
        for (gy in 0 until 7) {
            val sy0 = comp.minY + (gy * dh) / 7
            val sy1 = (comp.minY + ((gy + 1) * dh) / 7 - 1).coerceAtLeast(sy0)
            for (gx in 0 until 5) {
                val sx0 = comp.minX + (gx * dw) / 5
                val sx1 = (comp.minX + ((gx + 1) * dw) / 5 - 1).coerceAtLeast(sx0)
                var hits = 0
                var total = 0
                for (yy in sy0..sy1) {
                    val off = yy * scanW
                    for (xx in sx0..sx1) {
                        if (mask[off + xx]) hits++
                        total++
                    }
                }
                grid[gy * 5 + gx] = if (total > 0) hits.toFloat() / total else 0f
            }
        }

        fun zone(gxRange: IntRange, gyRange: IntRange): Float {
            var sum = 0f
            var cnt = 0
            for (gy in gyRange) {
                for (gx in gxRange) {
                    sum += grid[gy * 5 + gx]
                    cnt++
                }
            }
            return if (cnt > 0) sum / cnt else 0f
        }

        // Narrow bar / serif "1"
        if (aspect < 0.48f) {
            return 1
        }

        val topBar = zone(1..3, 0..1)
        val midCenter = zone(1..3, 2..4)
        val midExact = zone(1..3, 3..3)
        val botBar = zone(1..3, 6..6)
        val crossbar4 = zone(1..3, 4..5)
        val leftTop = zone(0..1, 1..2)
        val leftBot = zone(0..1, 4..5)
        val rightTop = zone(3..4, 1..2)
        val rightBot = zone(3..4, 4..5)
        val centerHole = grid[3 * 5 + 2]
        val botLeftCorner = grid[6 * 5 + 0]
        val botRightCorner = grid[6 * 5 + 4]
        val veryBottomLeft = zone(0..1, 6..6)

        // Count vertical transitions (segments crossed) along x=50% and horizontal transitions at y=28%, 72%
        val midX = comp.minX + dw / 2
        val vCuts = countRunsAlongCol(mask, scanW, midX, comp.minY, comp.maxY)
        val yUpper = comp.minY + (dh * 0.28f).roundToInt()
        val yLower = comp.minY + (dh * 0.72f).roundToInt()
        val hUpperCuts = countRunsAlongRow(mask, scanW, yUpper, comp.minX, comp.maxX)
        val hLowerCuts = countRunsAlongRow(mask, scanW, yLower, comp.minX, comp.maxX)

        // Classify against structural templates of digits 0..9 in bold rounded game font
        // Check 1 first if right-stem dominated with hollow lower-left
        if (aspect < 0.56f && leftBot < 0.22f && botBar < 0.35f) return 1

        // 4: open top-right / tapered top peak, thick crossbar at gy=4..5, and only narrow right-stem leg at gy=6
        if (veryBottomLeft < 0.22f && botRightCorner < 0.28f && botBar < 0.50f && crossbar4 > 0.55f && topBar < 0.62f) {
            return 4
        }

        // 7: strong top bar, almost empty bottom-left & bottom-right corners (diagonal/stem), weak mid-left
        if (topBar > 0.45f && botLeftCorner < 0.25f && botRightCorner < 0.25f && leftBot < 0.35f && midExact < 0.55f) {
            return 7
        }

        // Use exact segment distance scoring across 0, 2, 3, 5, 6, 7, 8, 9, 4
        data class DigitTemplate(
            val digit: Int,
            val lTop: Float, val lBot: Float,
            val rTop: Float, val rBot: Float,
            val tBar: Float, val mBar: Float, val bBar: Float,
            val expVc: Float, val expHu: Float, val expHl: Float
        )

        val templates = listOf(
            DigitTemplate(0, 0.85f, 0.85f, 0.85f, 0.85f, 0.85f, 0.12f, 0.85f, 2f, 2f, 2f),
            DigitTemplate(2, 0.18f, 0.80f, 0.80f, 0.18f, 0.80f, 0.72f, 0.85f, 3f, 1f, 1f),
            DigitTemplate(3, 0.18f, 0.18f, 0.85f, 0.85f, 0.82f, 0.78f, 0.82f, 3f, 1f, 1f),
            DigitTemplate(4, 0.45f, 0.40f, 0.35f, 0.65f, 0.32f, 0.60f, 0.28f, 2f, 2f, 1f),
            DigitTemplate(5, 0.85f, 0.18f, 0.18f, 0.85f, 0.82f, 0.78f, 0.82f, 3f, 1f, 1f),
            DigitTemplate(6, 0.85f, 0.85f, 0.18f, 0.85f, 0.78f, 0.75f, 0.85f, 3f, 1f, 2f),
            DigitTemplate(7, 0.18f, 0.15f, 0.75f, 0.28f, 0.88f, 0.35f, 0.22f, 2f, 1f, 1f),
            DigitTemplate(8, 0.85f, 0.85f, 0.85f, 0.85f, 0.85f, 0.85f, 0.85f, 3f, 2f, 2f),
            DigitTemplate(9, 0.85f, 0.18f, 0.85f, 0.85f, 0.85f, 0.78f, 0.78f, 3f, 2f, 1f)
        )

        // Pick closest structural match
        var bestDigit = 0
        var bestErr = Float.MAX_VALUE
        for (tpl in templates) {
            val segErr =
                abs(leftTop - tpl.lTop) +
                    abs(leftBot - tpl.lBot) +
                    abs(rightTop - tpl.rTop) +
                    abs(rightBot - tpl.rBot) +
                    abs(topBar - tpl.tBar) +
                    abs(midExact - tpl.mBar) * 1.45f +
                    abs(botBar - tpl.bBar) * 1.35f
            val cutErr =
                abs(vCuts - tpl.expVc) * 0.22f +
                    abs(hUpperCuts - tpl.expHu) * 0.35f +
                    abs(hLowerCuts - tpl.expHl) * 0.35f
            // Disambiguate 0 vs 8 cleanly by inner center hole vs horizontal stroke
            val holePenalty = when (tpl.digit) {
                0 -> centerHole * 1.4f + midCenter * 0.5f
                8 -> if (centerHole < 0.22f && vCuts <= 2) 1.5f else 0f
                else -> 0f
            }
            val totalErr = segErr + cutErr + holePenalty
            if (totalErr < bestErr) {
                bestErr = totalErr
                bestDigit = tpl.digit
            }
        }
        return bestDigit
    }

    private fun countRunsAlongCol(mask: BooleanArray, scanW: Int, rx: Int, y0: Int, y1: Int): Int {
        var runs = 0
        var inRun = false
        for (y in y0..y1) {
            val fg = mask[y * scanW + rx]
            if (fg && !inRun) {
                runs++
                inRun = true
            } else if (!fg) {
                inRun = false
            }
        }
        return runs
    }

    private fun countRunsAlongRow(mask: BooleanArray, scanW: Int, ry: Int, x0: Int, x1: Int): Int {
        var runs = 0
        var inRun = false
        val rowOff = ry * scanW
        for (x in x0..x1) {
            val fg = mask[rowOff + x]
            if (fg && !inRun) {
                runs++
                inRun = true
            } else if (!fg) {
                inRun = false
            }
        }
        return runs
    }
}
