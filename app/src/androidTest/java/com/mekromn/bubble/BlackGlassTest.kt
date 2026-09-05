package com.mekromn.bubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel/material regression only. A software Canvas test does NOT establish GPU performance. */
@RunWith(AndroidJUnit4::class)
class BlackGlassTest {
    @Test fun bubbleIsNeutralGreyWithTransparentEdgesAndShadedGlass() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            val bubble=GlassBubble(instrumentation.targetContext)
            bubble.layout(0,0,256,256)
            bubble.draw(Canvas(image))
        }
        assertEquals(0,Color.alpha(image.getPixel(0,0)))
        var visible=0; var translucent=0
        val levels=HashSet<Int>()
        for(y in 0 until 256 step 2)for(x in 0 until 256 step 2) {
            val pixel=image.getPixel(x,y)
            if(Color.alpha(pixel)>0) {
                visible++
                if(Color.alpha(pixel)<255)translucent++
                assertTrue("Tinted blue/green bubble pixel",kotlin.math.abs(Color.red(pixel)-Color.blue(pixel))<=1)
                assertTrue(kotlin.math.abs(Color.green(pixel)-Color.red(pixel))<=1)
                levels+=Color.red(pixel)
            }
        }
        assertTrue(visible>1000)
        assertTrue("Missing transparent black-glass material",translucent>1000)
        assertTrue("Flat fill instead of shaded glass",levels.size>20)
        image.recycle()
    }
}
