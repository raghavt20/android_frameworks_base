/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.FrameLayout
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.doze.DozeReceiver
import com.android.systemui.res.R

import vendor.xiaomi.hw.touchfeature.V1_0.ITouchFeature
import vendor.xiaomi.hardware.fingerprintextension.V1_0.IXiaomiFingerprint

import android.os.Handler
import android.os.HandlerThread

private const val TAG = "UdfpsView"

/**
 * The main view group containing all UDFPS animations.
 */
class UdfpsView(
    context: Context,
    attrs: AttributeSet?
) : FrameLayout(context, attrs), DozeReceiver {
    // sensorRect may be bigger than the sensor. True sensor dimensions are defined in
    // overlayParams.sensorBounds
    var sensorRect = Rect()
    private var mUdfpsDisplayMode: UdfpsDisplayModeProvider? = null
    private val debugTextPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLUE
        textSize = 32f
    }

    private var ghbmView: UdfpsSurfaceView? = null

    /** View controller (can be different for enrollment, BiometricPrompt, Keyguard, etc.). */
    var animationViewController: UdfpsAnimationViewController<*>? = null

    /** Parameters that affect the position and size of the overlay. */
    var overlayParams = UdfpsOverlayParams()

    /** Debug message. */
    var debugMessage: String? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** True after the call to [configureDisplay] and before the call to [unconfigureDisplay]. */
    var isDisplayConfigured: Boolean = false
        private set

    fun setUdfpsDisplayModeProvider(udfpsDisplayModeProvider: UdfpsDisplayModeProvider?) {
        mUdfpsDisplayMode = udfpsDisplayModeProvider
    }

    // Don't propagate any touch events to the child views.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return (animationViewController == null || !animationViewController!!.shouldPauseAuth())
    }

    override fun onFinishInflate() {
        ghbmView = findViewById(R.id.hbm_view)
    }

    override fun dozeTimeTick() {
        animationViewController?.dozeTimeTick()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        // Updates sensor rect in relation to the overlay view
        animationViewController?.onSensorRectUpdated(RectF(sensorRect))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.v(TAG, "onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.v(TAG, "onDetachedFromWindow")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDisplayConfigured) {
            if (!debugMessage.isNullOrEmpty()) {
                canvas.drawText(debugMessage!!, 0f, 160f, debugTextPaint)
            }
        }
    }

    val xiaomiDispParam = "/sys/class/mi_display/disp-DSI-0/disp_param"
    var hasXiaomiLhbm = File(xiaomiDispParam).exists()

    private val handlerThread = HandlerThread("UDFPS").also { it.start() }
    val myHandler = Handler(handlerThread.looper)

    fun configureDisplay(onDisplayConfigured: Runnable) {
        isDisplayConfigured = true
        animationViewController?.onDisplayConfiguring()
        val gView = ghbmView
        if (gView != null) {
            gView.setGhbmIlluminationListener(this::doIlluminate)
            gView.visibility = VISIBLE
            gView.startGhbmIllumination(onDisplayConfigured)
        } else {
            doIlluminate(null /* surface */, onDisplayConfigured)
        }
    }

    private fun doIlluminate(surface: Surface?, onDisplayConfigured: Runnable?) {
        if (ghbmView != null && surface == null) {
            Log.e(TAG, "doIlluminate | surface must be non-null for GHBM")
        }

        mUdfpsDisplayMode?.enable {
            onDisplayConfigured?.run()
            ghbmView?.drawIlluminationDot(RectF(sensorRect))
        }

        Log.d("PHH-Enroll", "Xiaomi scenario in UdfpsView reached!")
        mySurfaceView.setVisibility(INVISIBLE)

        IXiaomiFingerprint.getService().extCmd(android.os.SystemProperties.getInt("persist.phh.xiaomi.fod.enrollment.id", 4), 1);

        var res = ITouchFeature.getService().setTouchMode(0, 10, 1);

        if(res != 0){
            Log.d("PHH-Enroll", "SetTouchMode 10,1 was NOT executed successfully. Res is " + res)
        }

        myHandler.postDelayed({

            var ret200 = ITouchFeature.getService().setTouchMode(0, 10, 1);
            if(ret200 != 0){
                Log.d("PHH-Enroll", "myHandler.postDelayed 200ms -SetTouchMode was NOT executed successfully. Ret is " + ret200)
            }

            myHandler.postDelayed({
                Log.d("PHH-Enroll", "myHandler.postDelayed 600ms - line prior to setTouchMode 10,0")

                var ret600 = ITouchFeature.getService().setTouchMode(0, 10, 0);

                if(ret600 != 0){
                    Log.d("PHH-Enroll", "myHandler.postDelayed 600ms -SetTouchMode 10,0 was NOT executed successfully. Ret is " + ret600)
                }
            }, 600)

        }, 200)
    }

    fun unconfigureDisplay() {
        isDisplayConfigured = false
        animationViewController?.onDisplayUnconfigured()
        ghbmView?.let { view ->
            view.setGhbmIlluminationListener(null)
            view.visibility = INVISIBLE
        }
        mUdfpsDisplayMode?.disable(null /* onDisabled */)

        IXiaomiFingerprint.getService().extCmd(android.os.SystemProperties.getInt("persist.phh.xiaomi.fod.enrollment.id", 4), 0);
        ITouchFeature.getService().setTouchMode(0, 10, 0);

        mySurfaceView.setVisibility(INVISIBLE)
        Log.d("PHH", "setting surface invisible!")
    }
}
