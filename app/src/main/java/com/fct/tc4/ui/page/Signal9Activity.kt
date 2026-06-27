// Signal9Activity.kt -- This file is part of tiny_container.
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

package com.fct.tc4.ui.page

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.LinearInterpolator
import androidx.core.animation.ValueAnimator
import androidx.core.view.isVisible
import com.fct.tc4.databinding.Tc4ActivitySignal9Binding
import kotlin.math.sin
import kotlin.random.Random

class Signal9Activity : AppCompatActivity() {

    private lateinit var binding: Tc4ActivitySignal9Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = Tc4ActivitySignal9Binding.inflate(layoutInflater)
        setContentView(binding.root)

        startCrazyAnimation(binding)

        setupContent()

        binding.kaomojiText.setOnClickListener {
            binding.kaomojiText.text = "O_O "
        }
    }

    private fun setupContent() {

        // 根据Android版本显示不同的解决方案
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14以下版本
            binding.preAndroid14Layout.isVisible = true

            binding.toolButton.setOnClickListener {
                openBrowserLink("https://www.vmos.cn/zhushou.htm")
            }

            binding.tutorialButton.setOnClickListener {
                openBrowserLink("https://mp.weixin.qq.com/s/lgvE6uE8SWYJHE756XoHkA")
            }
        } else {
            // Android 14及以上版本
            binding.solutionAndroid14.isVisible = true
        }
    }

    private fun openBrowserLink(url: String) {
        if (url.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }

    // 在Activity/Fragment的onCreate/onViewCreated中调用
    private var animator: ValueAnimator? = null
    private var handler: Handler? = null
    private var runnable: Runnable? = null

    fun startCrazyAnimation(binding: Tc4ActivitySignal9Binding) {
        stopCrazyAnimation() // 先清理

        val textView = binding.kaomojiText
        val random = Random

        textView.post {
            textView.pivotX = 0f
            textView.pivotY = textView.height.toFloat() * 1.1f
        }

        // scale动画
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val angle = animatedFraction * 2 * Math.PI
                val scaleY = 1f + 0.1f * (1 + sin(angle)).toFloat()
                textView.scaleY = scaleY
                textView.scaleX = 1f / scaleY
            }
            start()
        }

        handler = Handler(Looper.getMainLooper())
        runnable = object : Runnable {
            override fun run() {
                if (random.nextFloat() < 0.25f) {
                    textView.text = "-_- "
                    handler?.postDelayed({ textView.text = ">_< " }, 100)
                }
                if (random.nextFloat() < 0.01f) {
                    textView.text = "-_- "
                    handler?.postDelayed({ textView.text = "OvO " }, 100)
                }
                handler?.postDelayed(this, 1000)
            }
        }
        handler?.post(runnable!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCrazyAnimation()
    }

    fun stopCrazyAnimation() {
        animator?.cancel()
        animator = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        runnable = null
    }
}