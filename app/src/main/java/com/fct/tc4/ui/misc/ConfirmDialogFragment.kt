// ConfirmDialogFragment.kt -- This file is part of tiny_container.
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

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 通用确认对话框 DialogFragment。
 * 调用方需自行在稳定的 lifecycle 上注册 setFragmentResultListener。
 *
 * 用法：
 *   val requestKey = "confirm_xxx"
 *   childFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
 *       if (bundle.getBoolean("confirmed")) { /* 确认 */ }
 *   }
 *   ConfirmDialogFragment.show(childFragmentManager, requestKey, "确认删除", "确定吗？", "删除")
 */
class ConfirmDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY)!!

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getString(ARG_TITLE))
            .setMessage(args.getString(ARG_MESSAGE))
            .setPositiveButton(args.getString(ARG_POSITIVE)) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putBoolean(KEY_CONFIRMED, true) })
            }
            .setNegativeButton(args.getString(ARG_NEGATIVE)) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putBoolean(KEY_CONFIRMED, false) })
            }

        val neutralText = args.getString(ARG_NEUTRAL)
        if (neutralText != null) {
            builder.setNeutralButton(neutralText) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putBoolean(KEY_CONFIRMED, false)
                    putBoolean(KEY_NEUTRAL, true)
                })
            }
        }

        return builder.create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val requestKey = requireArguments().getString(ARG_REQUEST_KEY) ?: return
        parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putBoolean(KEY_CONFIRMED, false) })
    }

    companion object {
        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE = "positive"
        private const val ARG_NEGATIVE = "negative"
        private const val ARG_NEUTRAL = "neutral"
        const val KEY_CONFIRMED = "confirmed"
        const val KEY_NEUTRAL = "neutral"

        fun show(
            fragmentManager: FragmentManager,
            requestKey: String,
            title: String? = null,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null
        ) {
            ConfirmDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putString(ARG_TITLE, title)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE, positiveText)
                    putString(ARG_NEGATIVE, negativeText)
                    if (neutralText != null) {
                        putString(ARG_NEUTRAL, neutralText)
                    }
                }
            }.show(fragmentManager, "confirm_$requestKey")
        }
    }
}
