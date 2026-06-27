// FeatureEditDialogFragment.kt -- This file is part of tiny_container.
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
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4DialogFeatureEditBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FeatureEditDialogFragment : DialogFragment() {

    private var _binding: Tc4DialogFeatureEditBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = Tc4DialogFeatureEditBinding.inflate(requireActivity().layoutInflater)
        val args = requireArguments()
        val type = args.getString(ARG_TYPE) ?: ""

        // 根据类型显示/隐藏字段
        when (type) {
            "x11" -> {
                binding.linkLayout.visibility = android.view.View.GONE
                binding.pathLayout.visibility = android.view.View.GONE
                binding.argsLayout.visibility = android.view.View.VISIBLE
            }
            "lstat-cache" -> {
                binding.cmdLayout.visibility = android.view.View.GONE
                binding.linkLayout.visibility = android.view.View.GONE
                binding.argsLayout.visibility = android.view.View.GONE
                binding.pathLayout.visibility = android.view.View.VISIBLE
            }
        }

        // 填入初始值（优先从 savedInstanceState 恢复）
        binding.nameInput.setText(savedInstanceState?.getString(SAVED_NAME) ?: args.getString(ARG_NAME))
        binding.descInput.setText(savedInstanceState?.getString(SAVED_DESC) ?: args.getString(ARG_DESC))
        binding.cmdInput.setText(savedInstanceState?.getString(SAVED_CMD) ?: args.getString(ARG_CMD))
        binding.linkInput.setText(savedInstanceState?.getString(SAVED_LINK) ?: args.getString(ARG_LINK))
        binding.pathInput.setText(savedInstanceState?.getString(SAVED_PATH) ?: args.getString(ARG_PATH))
        binding.argsInput.setText(savedInstanceState?.getString(SAVED_ARGS) ?: args.getString(ARG_ARGS))

        // lstat-cache 类型隐藏"保存并发送命令"按钮（无命令可发送）
        val neutralText = if (type == "lstat-cache") null else getString(R.string.tc4_btn_save_send)

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setNegativeButton(R.string.tc4_btn_cancel, null)
            .apply { if (neutralText != null) setNeutralButton(neutralText, null) }
            .setPositiveButton(R.string.tc4_btn_save_exit, null)
            .create()
            .also { dialog ->
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                )
            }
    }

    override fun onStart() {
        super.onStart()
        val alertDialog = dialog as? androidx.appcompat.app.AlertDialog ?: return

        // 保存并退出
        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            sendResult(REQUEST_SAVE_EXIT)
            dismiss()
        }

        // 保存并发送命令（不关闭对话框）
        alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            sendResult(REQUEST_SAVE_SEND)
        }

        // "取消"按钮使用默认 dismiss 行为
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVED_NAME, binding.nameInput.text?.toString() ?: "")
        outState.putString(SAVED_DESC, binding.descInput.text?.toString() ?: "")
        outState.putString(SAVED_CMD, binding.cmdInput.text?.toString() ?: "")
        outState.putString(SAVED_LINK, binding.linkInput.text?.toString() ?: "")
        outState.putString(SAVED_PATH, binding.pathInput.text?.toString() ?: "")
        outState.putString(SAVED_ARGS, binding.argsInput.text?.toString() ?: "")
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // 点击外部区域取消不做任何保存
    }

    private fun sendResult(requestKey: String) {
        val args = requireArguments()
        val type = args.getString(ARG_TYPE) ?: ""
        parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
            putInt(KEY_INDEX, args.getInt(ARG_INDEX))
            putString(KEY_TYPE, type)
            putString(KEY_NAME, binding.nameInput.text?.toString() ?: "")
            putString(KEY_DESC, binding.descInput.text?.toString() ?: "")
            putString(KEY_CMD, binding.cmdInput.text?.toString() ?: "")
            putString(KEY_LINK, binding.linkInput.text?.toString() ?: "")

            if (type == "lstat-cache") {
                val pathList = parseLines(binding.pathInput.text?.toString())
                if (pathList.isNotEmpty()) putStringArrayList(KEY_PATH, ArrayList(pathList))
            }
            if (type == "x11") {
                val argsList = parseLines(binding.argsInput.text?.toString())
                if (argsList.isNotEmpty()) putStringArrayList(KEY_ARGS, ArrayList(argsList))
            }
        })
    }

    private fun parseLines(text: String?): List<String> {
        return text?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_SAVE_SEND = "feature_edit_save_send"
        const val REQUEST_SAVE_EXIT = "feature_edit_save_exit"

        const val KEY_INDEX = "index"
        const val KEY_TYPE = "type"
        const val KEY_NAME = "name"
        const val KEY_DESC = "description"
        const val KEY_CMD = "command"
        const val KEY_LINK = "link"
        const val KEY_PATH = "path"
        const val KEY_ARGS = "args"

        private const val ARG_INDEX = "arg_index"
        private const val ARG_TYPE = "arg_type"
        private const val ARG_NAME = "arg_name"
        private const val ARG_DESC = "arg_desc"
        private const val ARG_CMD = "arg_cmd"
        private const val ARG_LINK = "arg_link"
        private const val ARG_PATH = "arg_path"
        private const val ARG_ARGS = "arg_args"

        private const val SAVED_NAME = "saved_name"
        private const val SAVED_DESC = "saved_desc"
        private const val SAVED_CMD = "saved_cmd"
        private const val SAVED_LINK = "saved_link"
        private const val SAVED_PATH = "saved_path"
        private const val SAVED_ARGS = "saved_args"

        fun newInstance(
            type: String,
            index: Int,
            name: String,
            description: String,
            command: String,
            link: String,
            path: String = "",
            args: String = ""
        ) = FeatureEditDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TYPE, type)
                putInt(ARG_INDEX, index)
                putString(ARG_NAME, name)
                putString(ARG_DESC, description)
                putString(ARG_CMD, command)
                putString(ARG_LINK, link)
                putString(ARG_PATH, path)
                putString(ARG_ARGS, args)
            }
        }
    }
}
