package ai.toolus.mobileguide

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream

/**
 * artemis Flash observes one raw screenshot per turn next to the element list (runner.py `_build_tail`).
 * AccessibilityService.takeScreenshot (API 30+) needs canTakeScreenshot; the OS refuses windows that set
 * FLAG_SECURE (ERROR_TAKE_SCREENSHOT_SECURE_WINDOW), which is the Play requirement anyway.
 */
object Screenshotter {
    fun capture(service: AccessibilityService, maxWidth: Int = 720, done: (jpegBase64: String?, error: String?) -> Unit) {
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    if (bmp == null) { done(null, "wrap-failed"); return }
                    val scale = maxWidth.toFloat() / bmp.width
                    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * scale).toInt(), true) else bmp
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    done(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), null)
                }

                override fun onFailure(errorCode: Int) {
                    done(null, when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure-window"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "interval-too-short"
                        else -> "error-$errorCode"
                    })
                }
            })
        } catch (e: Exception) {
            done(null, "exception: ${e.message}")
        }
    }
}
