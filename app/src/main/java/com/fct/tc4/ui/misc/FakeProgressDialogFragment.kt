// FakeProgressDialogFragment.kt -- This file is part of tiny_container.
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
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.fct.tc4.databinding.Tc4DialogFakeProgressBinding
import com.fct.tc4.ui.page.ContainerManageViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 通用假进度对话框。
 * 显示假进度条和操作描述，不可取消。
 *
 * 用法：
 *   val dialog = FakeProgressDialogFragment
 *       .newBuilder(childFragmentManager)
 *       .startTime(System.currentTimeMillis())
 *       .containerSizeBytes(file.length())
 *       .initialTitle("正在删除旧容器文件...")
 *       .show("progress")
 *   // 后续更新：
 *   dialog?.updateTitle("正在导出容器文件...")
 *   // 完成后：
 *   dialog?.dismiss()
 */
class FakeProgressDialogFragment : DialogFragment() {

    private var _binding: Tc4DialogFakeProgressBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ContainerManageViewModel by activityViewModels()
    private var progressJob: Job? = null
    private var pendingTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = Tc4DialogFakeProgressBinding.inflate(requireActivity().layoutInflater)

        val startTime = arguments?.getLong(ARG_START_TIME) ?: System.currentTimeMillis()

        pendingTitle?.let { binding.title.text = it }
        pendingTitle = null
        val initialTitle = arguments?.getString(ARG_INITIAL_TITLE)
        if (initialTitle != null) binding.title.text = initialTitle

        progressJob = lifecycleScope.launch {
            while (isActive) {
                val progress = viewModel.fakeProgress(startTime, arguments?.getLong(ARG_CONTAINER_SIZE) ?: 0L)
                binding.progressIndicator.setProgressCompat((progress * 100).toInt(), true)
                delay(200)
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .create()
    }

    fun updateTitle(text: String) {
        if (_binding == null) {
            pendingTitle = text
            return
        }
        binding.title.text = text
    }

    override fun onDestroyView() {
        progressJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_START_TIME = "start_time"
        private const val ARG_CONTAINER_SIZE = "container_size"
        private const val ARG_INITIAL_TITLE = "initial_title"

        fun newBuilder(fragmentManager: FragmentManager): Builder = Builder(fragmentManager)
    }

    class Builder(private val fragmentManager: FragmentManager) {
        private var startTime: Long = System.currentTimeMillis()
        private var containerSizeBytes: Long = 0L
        private var initialTitle: String = ""

        fun startTime(value: Long) = apply { startTime = value }
        fun containerSizeBytes(value: Long) = apply { containerSizeBytes = value }
        fun initialTitle(value: String) = apply { initialTitle = value }

        fun show(tag: String = "progress_${hashCode()}"): FakeProgressDialogFragment? {
            if (fragmentManager.findFragmentByTag(tag) != null) return null
            val fragment = FakeProgressDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_START_TIME, startTime)
                    putLong(ARG_CONTAINER_SIZE, containerSizeBytes)
                    putString(ARG_INITIAL_TITLE, initialTitle)
                }
            }
            fragment.show(fragmentManager, tag)
            return fragment
        }
    }
}
