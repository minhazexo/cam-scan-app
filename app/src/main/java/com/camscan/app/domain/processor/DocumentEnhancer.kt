package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.Color
import com.camscan.app.domain.model.FilterMode
import kotlin.math.max
import kotlin.math.min

object DocumentEnhancer {

    fun enhance(
        bitmap: Bitmap,
        mode: FilterMode,
        brightnessOffset: Int = 0,
        contrastMultiplier: Float = 1.0f
    ): Bitmap {
        if (mode == FilterMode.ORIGINAL && brightnessOffset == 0 && contrastMultiplier == 1.0f) {
            return bitmap
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        when (mode) {
            FilterMode.AUTO, FilterMode.DOCUMENT -> {
                enhanceGcmode(pixels, width, height)
            }
            FilterMode.LIGHTEN -> {
                enhanceRmode(pixels, 66f, 160f)
            }
            FilterMode.GRAYSCALE -> {
                toGrayscale(pixels)
            }
            FilterMode.BLACK_AND_WHITE -> {
                toSmodeBlackAndWhite(pixels)
            }
            FilterMode.HIGH_CONTRAST -> {
                toHighContrast(pixels)
            }
            FilterMode.ORIGINAL -> { /* Keep raw */ }
        }

        if (brightnessOffset != 0 || contrastMultiplier != 1.0f) {
            applyBrightnessContrast(pixels, brightnessOffset, contrastMultiplier)
        }

        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Port of GCMODE / auto_scan.py:
     * 1. High Pass Filter: img - boxBlur(img) + 127 per BGR channel
     * 2. White point stretch: [0, 127] -> [0, 255]
     * 3. Black point stretch 1: bp = 66
     * 4. Black point stretch 2: bp2 = 20
     * 5. Color saturation boost (1.25x)
     */
    private fun enhanceGcmode(pixels: IntArray, width: Int, height: Int) {
        val size = pixels.size
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)

        for (i in 0 until size) {
            val c = pixels[i]
            r[i] = ((c shr 16) and 0xFF).toFloat()
            g[i] = ((c shr 8) and 0xFF).toFloat()
            b[i] = (c and 0xFF).toFloat()
        }

        // Fast Box Blur for background estimation
        val minDim = min(width, height)
        val kSize = (minDim / 8).coerceIn(21, 101) or 1
        val blurR = FloatArray(size)
        val blurG = FloatArray(size)
        val blurB = FloatArray(size)

        boxBlurChannel(r, blurR, width, height, kSize)
        boxBlurChannel(g, blurG, width, height, kSize)
        boxBlurChannel(b, blurB, width, height, kSize)

        val wp = 127f
        val bp = 66f
        val bp2 = 20f
        val saturate = 1.25f

        for (i in 0 until size) {
            // High Pass Flatten: img - bg + 127
            var fr = r[i] - blurR[i] + 127f
            var fg = g[i] - blurG[i] + 127f
            var fb = b[i] - blurB[i] + 127f

            // Color saturation boost around mean luminance
            val lum = (fr + fg + fb) / 3f
            fr = lum + (fr - lum) * saturate
            fg = lum + (fg - lum) * saturate
            fb = lum + (fb - lum) * saturate

            // White point stretch [0, wp] -> [0, 255]
            fr = (min(fr, wp) * (255f / wp)).coerceIn(0f, 255f)
            fg = (min(fg, wp) * (255f / wp)).coerceIn(0f, 255f)
            fb = (min(fb, wp) * (255f / wp)).coerceIn(0f, 255f)

            // Black point stretch 1 (bp = 66)
            fr = ((fr - bp) * (255f / (255f - bp))).coerceIn(0f, 255f)
            fg = ((fg - bp) * (255f / (255f - bp))).coerceIn(0f, 255f)
            fb = ((fb - bp) * (255f / (255f - bp))).coerceIn(0f, 255f)

            // Black point stretch 2 (bp2 = 20)
            fr = ((fr - bp2) * (255f / (255f - bp2))).coerceIn(0f, 255f)
            fg = ((fg - bp2) * (255f / (255f - bp2))).coerceIn(0f, 255f)
            fb = ((fb - bp2) * (255f / (255f - bp2))).coerceIn(0f, 255f)

            pixels[i] = Color.rgb(fr.toInt(), fg.toInt(), fb.toInt())
        }
    }

    private fun enhanceRmode(pixels: IntArray, blackPoint: Float, whitePoint: Float) {
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF

            fun stretch(v: Int): Int {
                var valF = (v - blackPoint) * (255f / (255f - blackPoint))
                valF = min(valF, whitePoint) * (255f / whitePoint)
                return valF.toInt().coerceIn(0, 255)
            }

            pixels[i] = Color.rgb(stretch(r), stretch(g), stretch(b))
        }
    }

    private fun toGrayscale(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(gray, gray, gray)
        }
    }

    private fun toSmodeBlackAndWhite(pixels: IntArray) {
        // LAB color difference subtraction (scan.py SMODE)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f

            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) * 255f
            val aChannel = (r - g) * 128f + 128f
            val bChannel = (g - b) * 128f + 128f

            val bwVal = ((lum - bChannel) + (lum - aChannel)).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(bwVal, bwVal, bwVal)
        }
    }

    private fun toHighContrast(pixels: IntArray) {
        var sum = 0L
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sum += (r + g + b) / 3
        }
        val avgThreshold = (sum / pixels.size).toInt().coerceIn(80, 180)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            val bw = if (gray > avgThreshold - 10) 255 else 0
            pixels[i] = Color.rgb(bw, bw, bw)
        }
    }

    private fun applyBrightnessContrast(pixels: IntArray, brightness: Int, contrast: Float) {
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF

            r = (((r - 128) * contrast + 128) + brightness).toInt().coerceIn(0, 255)
            g = (((g - 128) * contrast + 128) + brightness).toInt().coerceIn(0, 255)
            b = (((b - 128) * contrast + 128) + brightness).toInt().coerceIn(0, 255)

            pixels[i] = Color.rgb(r, g, b)
        }
    }

    private fun boxBlurChannel(src: FloatArray, dst: FloatArray, w: Int, h: Int, radius: Int) {
        val temp = FloatArray(src.size)
        val r = radius / 2

        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            var windowSum = 0f
            var count = 0

            for (x in -r..r) {
                if (x in 0 until w) {
                    windowSum += src[rowOffset + x]
                    count++
                }
            }

            for (x in 0 until w) {
                temp[rowOffset + x] = windowSum / count

                val addIdx = x + r + 1
                if (addIdx < w) {
                    windowSum += src[rowOffset + addIdx]
                    count++
                }
                val remIdx = x - r
                if (remIdx >= 0) {
                    windowSum -= src[rowOffset + remIdx]
                    count--
                }
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var windowSum = 0f
            var count = 0

            for (y in -r..r) {
                if (y in 0 until h) {
                    windowSum += temp[y * w + x]
                    count++
                }
            }

            for (y in 0 until h) {
                dst[y * w + x] = windowSum / count

                val addIdx = y + r + 1
                if (addIdx < h) {
                    windowSum += temp[addIdx * w + x]
                    count++
                }
                val remIdx = y - r
                if (remIdx >= 0) {
                    windowSum -= temp[remIdx * w + x]
                    count--
                }
            }
        }
    }
}
