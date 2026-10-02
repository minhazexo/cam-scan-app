package com.camscan.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.DocumentProcessor
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end CAPTURE-PATH check on a real device.
 *
 * Reads images pushed to shared storage under `Pictures/import_test/`
 * (sample*.jpg; requires the READ_MEDIA_IMAGES grant), runs the ACTUAL capture
 * pipeline ([DocumentDetector.detectDocument] + [DocumentProcessor.processImageStrict])
 * and writes the A4 output plus a text report to the app's external files dir
 * `import_out/` so the result can be pulled and inspected. Skips when no
 * inputs are present.
 */
@RunWith(AndroidJUnit4::class)
class CapturePipelineInstrumentedTest {

    @Test
    fun processImportedSamples() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val inDirs = listOf(
            File("/sdcard/Pictures/import_test"),
            File("/storage/emulated/0/Pictures/import_test"),
            File(ctx.getExternalFilesDir(null), "import_test")
        )
        val inDir = inDirs.firstOrNull { it.isDirectory }
        assumeTrue("no import_test dir found under $inDirs", inDir != null)
        val inputs = inDir!!.listFiles { f ->
            f.isFile && f.name.startsWith("sample") && f.name.endsWith(".jpg")
        }?.sortedBy { it.name } ?: emptyList()
        assumeTrue("no sample*.jpg under $inDir", inputs.isNotEmpty())

        val outDir = File(ctx.getExternalFilesDir(null), "import_out")
        outDir.mkdirs()
        val report = StringBuilder()

        inputs.forEachIndexed { i, file ->
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp == null) {
                report.append("input=${file.name}: DECODE FAILED (${file.length()} bytes)\n")
                return@forEachIndexed
            }
            val det = DocumentDetector.detectDocument(bmp, fast = false)
            val st = DocumentDetector.lastStats
            report.append(
                "input=${file.name} ${bmp.width}x${bmp.height} " +
                    "conf=${det.confidence} digital=${det.digitalPage} score=%.3f".format(det.score) +
                    " corners=${det.corners != null} openCv=${st.openCvUsed} reason=${det.reason}\n"
            )
            det.corners?.let { c ->
                val pts = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft)
                    .joinToString(" ") { "(%.3f,%.3f)".format(it.x, it.y) }
                report.append("  corners=$pts\n")
            }
            if (det.corners != null) {
                try {
                    val res = DocumentProcessor.processImageStrict(bmp, corners = null, allowUnconfirmed = true)
                    writeJpeg(res.a4, File(outDir, "a4_$i.jpg"))
                    writeJpeg(res.documentOnly, File(outDir, "doc_$i.jpg"))
                    report.append(
                        "  scanned a4=${res.a4.width}x${res.a4.height} doc=${res.documentOnly.width}x${res.documentOnly.height} " +
                            "auto=${res.autoDetected} conf=${res.confidence} " +
                            "deskew=%.2f".format(res.deskewAngleApplied) + " dewarped=${res.dewarped}\n"
                    )
                } catch (e: Exception) {
                    report.append("  process failed: ${e.javaClass.simpleName}: ${e.message}\n")
                }
            } else {
                // Detection declined (dark / full-frame page). Force a
                // full-frame quad so we can see what the enhancement stage
                // would produce for a flat page photo.
                try {
                    val full = CornerPoints(
                        PointF(0.02f, 0.02f), PointF(0.98f, 0.02f),
                        PointF(0.98f, 0.98f), PointF(0.02f, 0.98f)
                    )
                    val res = DocumentProcessor.processImageStrict(bmp, corners = full)
                    writeJpeg(res.a4, File(outDir, "forced_a4_$i.jpg"))
                    writeJpeg(res.documentOnly, File(outDir, "forced_doc_$i.jpg"))
                    report.append(
                        "  FORCED full-frame a4=${res.a4.width}x${res.a4.height} " +
                            "doc=${res.documentOnly.width}x${res.documentOnly.height} conf=${res.confidence}\n"
                    )
                } catch (e: Exception) {
                    report.append("  forced scan failed: ${e.javaClass.simpleName}: ${e.message}\n")
                }
            }
            bmp.recycle()
        }

        File(outDir, "report.txt").writeText(report.toString())
        assertTrue("report written", File(outDir, "report.txt").exists())
    }

    private fun writeJpeg(bitmap: Bitmap, out: File) {
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        bitmap.recycle()
    }
}
