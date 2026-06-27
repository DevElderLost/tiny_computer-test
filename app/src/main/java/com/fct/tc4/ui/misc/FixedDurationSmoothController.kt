// FixedDurationSmoothController.kt -- This file is part of tiny_container.
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

import android.content.Context
import android.graphics.PointF
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class FixedDurationSmoothScroller(
    context: Context,
    private val fixedDurationMs: Int = 300
) : LinearSmoothScroller(context) {
    override fun calculateTimeForScrolling(dx: Int): Int = fixedDurationMs
    override fun calculateTimeForDeceleration(dx: Int): Int = fixedDurationMs
}

fun ViewPager2.setCurrentItemWithFixedDuration(position: Int, durationMs: Int = 300) {
    val recyclerView = getChildAt(0) as? RecyclerView ?: return
    val scroller = FixedDurationSmoothScroller(context, durationMs).apply {
        targetPosition = position
    }
    recyclerView.layoutManager?.startSmoothScroll(scroller)
}