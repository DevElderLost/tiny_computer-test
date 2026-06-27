// WipeLayout.kt -- This file is part of tiny_container.
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
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

class WipeLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // 定义擦除方向
    enum class Direction {
        LEFT_TO_RIGHT, TOP_TO_BOTTOM, RIGHT_TO_LEFT, BOTTOM_TO_TOP
    }

    private var animProgress = 1f // 1.0 = 完全可见, 0.0 = 完全隐藏
    private val clipRect = Rect()
    private var isAnimating = false
    private var currentDirection = Direction.TOP_TO_BOTTOM
    private var currentIsInMode = true

    /**
     * 执行擦除动画
     * @param isIn true 为进入(从无到有)，false 为退出(从有到无)
     * @param direction 擦除的方向，默认向下
     */
    fun startWipeAnimation(
        isIn: Boolean,
        direction: Direction = Direction.TOP_TO_BOTTOM,
        durationMs: Long = 500L,
        onEnd: (() -> Unit)? = null
    ) {
        currentDirection = direction
        currentIsInMode = isIn
        isAnimating = true

        val start = if (isIn) 0f else 1f
        val end = if (isIn) 1f else 0f

        // 进入动画开始前，先初始化 View 为不可见
        if (isIn) {
            animProgress = 0f
            updateClipRect() // 初始裁剪
            visibility = View.VISIBLE
        }

        val animator = ValueAnimator.ofFloat(start, end)
        animator.duration = durationMs
        animator.addUpdateListener { anim ->
            animProgress = anim.animatedValue as Float
            updateClipRect()
            invalidate() // 触发重绘
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
                if (!isIn) {
                    visibility = View.GONE // 退出动画结束，隐藏 View
                }
                onEnd?.invoke()
            }
        })
        animator.start()
    }

    /**
     * 核心逻辑：根据动画进度和方向，计算 Canvas 需要裁剪的矩形区域
     */
    private fun updateClipRect() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        if (animProgress >= 1f) {
            clipRect.set(0, 0, w, h)
            return
        }

        when (currentDirection) {
            Direction.TOP_TO_BOTTOM -> {
                // 向下
                if (currentIsInMode) {
                    // 进入：上方固定，底部向下扩展
                    clipRect.set(0, 0, w, (h * animProgress).toInt())
                } else {
                    // 退出：上方移动，底部固定
                    clipRect.set(0, (h * (1 - animProgress)).toInt(), w, h)
                }
            }
            Direction.LEFT_TO_RIGHT -> {
                // 向右
                if (currentIsInMode) {
                    clipRect.set(0, 0, (w * animProgress).toInt(), h)
                } else {
                    clipRect.set((w * (1 - animProgress)).toInt(), 0, w, h)
                }
            }
            // 向上和向左的逻辑类似，只是坐标计算反过来
            Direction.BOTTOM_TO_TOP -> {
                if (currentIsInMode) {
                    // 进入：底部固定，上方向上扩展
                    clipRect.set(0, (h * (1 - animProgress)).toInt(), w, h)
                } else {
                    // 退出：底部移动，上方固定
                    clipRect.set(0, 0, w, (h * animProgress).toInt())
                }
            }
            Direction.RIGHT_TO_LEFT -> {
                if (currentIsInMode) {
                    clipRect.set((w * (1 - animProgress)).toInt(), 0, w, h)
                } else {
                    clipRect.set(0, 0, (w * animProgress).toInt(), h)
                }
            }
        }
    }

    // 关键重写：在绘制子 View 之前，先裁剪 Canvas
    override fun dispatchDraw(canvas: Canvas) {
        if (!isAnimating || animProgress >= 1f) {
            // 如果不在动画中或全显状态，走正常绘制流
            super.dispatchDraw(canvas)
            return
        }

        canvas.save() // 保存当前状态
        canvas.clipRect(clipRect) // 应用裁剪
        super.dispatchDraw(canvas) // 绘制真实的子 View 树
        canvas.restore() // 恢复状态
    }
}