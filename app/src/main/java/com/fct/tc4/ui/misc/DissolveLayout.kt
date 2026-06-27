// DissolveLayout.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.misc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.random.Random
import androidx.core.graphics.createBitmap
import kotlin.math.ceil

class DissolveLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        isFilterBitmap = false
    }

    private var maskBitmap: Bitmap? = null
    private var maskPixels: IntArray? = null
    private var randomThresholds: FloatArray? = null
    private val maskMatrix = Matrix()

    // 进度：1.0 代表完全可见，0.0 代表完全溶解消失
    private var animProgress = 1f
    private val blockSize = 30

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val cols = ceil((w / blockSize.toFloat()).toDouble()).toInt()
        val rows = ceil((h / blockSize.toFloat()).toDouble()).toInt()

        maskBitmap = createBitmap(cols, rows)
        maskPixels = IntArray(cols * rows)
        randomThresholds = FloatArray(cols * rows) { Random.nextFloat() }
        maskMatrix.setScale(w.toFloat() / cols, h.toFloat() / rows)

        // 初始化遮罩
        updateMask()
    }

    /**
     * 执行溶解动画
     * @param isIn true 为进入(从无到有)，false 为退出(从有到无)
     */
    fun startDissolveAnimation(isIn: Boolean, durationMs: Long = 1000L, onEnd: (() -> Unit)? = null) {
        val start = if (isIn) 0f else 1f
        val end = if (isIn) 1f else 0f

        // 如果是进入动画，确保初始状态是不可见的
        if (isIn) {
            animProgress = 0f
            updateMask()
            visibility = View.VISIBLE
        }

        val animator = ValueAnimator.ofFloat(start, end)
        animator.duration = durationMs
        animator.addUpdateListener { anim ->
            animProgress = anim.animatedValue as Float
            updateMask()
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!isIn) {
                    visibility = View.GONE // 退出后隐藏
                }
                onEnd?.invoke()
            }
        })
        animator.start()
    }

    private fun updateMask() {
        val pixels = maskPixels ?: return
        val thresholds = randomThresholds ?: return

        for (i in pixels.indices) {
            // 核心逻辑：当前进度大于等于该像素点的随机阈值时，该点可见
            pixels[i] = if (animProgress >= thresholds[i]) Color.BLACK else Color.TRANSPARENT
        }
        maskBitmap?.setPixels(pixels, 0, maskBitmap!!.width, 0, 0, maskBitmap!!.width, maskBitmap!!.height)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // 如果动画已经结束且为全显状态，走常规绘制流程提升性能
        if (animProgress >= 1f) {
            super.dispatchDraw(canvas)
            return
        }
        // 如果全消失状态，直接不画
        if (animProgress <= 0f) return

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawBitmap(maskBitmap!!, maskMatrix, maskPaint)
        canvas.restoreToCount(layer)
    }
}