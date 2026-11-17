package com.arcticfoxapps.peeriche

import android.app.Activity
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import java.io.File

/** Read-only native rendering check; no preferences, accounts or user files are modified. */
internal fun brandAssetsSmoke(test: Instrumentation) {
    val result = Bundle()
    var code = Activity.RESULT_OK
    try {
        check(Build.VERSION.SDK_INT >= 33)
        val sheet = Bitmap.createBitmap(800, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        for ((index, night) in listOf(false, true).withIndex()) {
            val config = Configuration(test.targetContext.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = test.targetContext.createConfigurationContext(config)
            val left = index * 400f
            canvas.drawRect(left, 0f, left + 400, 360f, Paint().apply { color = if (night) Color.rgb(16, 18, 21) else Color.rgb(244, 245, 247) })
            val logo = BitmapFactory.decodeResource(context.resources, if (night) R.drawable.peeriche_logo_dark else R.drawable.peeriche_logo_light)
            check(logo.width.toFloat() / logo.height in 4f..5.5f)
            val height = 340f * logo.height / logo.width
            canvas.drawBitmap(logo, null, RectF(left + 30, 24f, left + 370, 24 + height), Paint(Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
            val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
            canvas.save(); canvas.translate(left + 30, 130f)
            icon.setBounds(0, 0, 160, 160); icon.draw(canvas)
            canvas.restore()
            val monochrome = checkNotNull(icon.monochrome).mutate()
            monochrome.setTint(if (night) Color.WHITE else Color.rgb(25, 35, 50))
            val themed = AdaptiveIconDrawable(ColorDrawable(if (night) Color.rgb(45, 55, 65) else Color.rgb(215, 225, 240)), monochrome)
            canvas.save(); canvas.translate(left + 210, 130f)
            themed.setBounds(0, 0, 160, 160); themed.draw(canvas)
            canvas.restore()
            canvas.drawText(if (night) "Dark / monochrome" else "Light / monochrome", left + 30, 330f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (night) Color.WHITE else Color.BLACK; textSize = 22f
            })
        }
        File(test.targetContext.cacheDir, "mobile-brand-preview.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
        val slate = kotlinx.coroutines.runBlocking {
            BroadcastPreferences(test.targetContext).slate(AccountSummary("brand-preview-only", "https://brand.invalid", "preview", "Brand preview", null, false))
        }
        File(test.targetContext.cacheDir, "mobile-brand-slate.png").outputStream().use { slate.compress(Bitmap.CompressFormat.PNG, 100, it) }
        slate.recycle()
        result.putString("stream", "PASS: Mobile light/dark horizontal logos, native adaptive icons, monochrome layers and default broadcast slate rendered; previews saved in cache\n")
    } catch (failure: Throwable) {
        code = Activity.RESULT_CANCELED
        result.putString("stream", "FAIL brand assets: ${failure.javaClass.simpleName}: ${failure.message}\n")
    }
    test.finish(code, result)
}
