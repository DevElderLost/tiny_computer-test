// QuickNodeEditDialogFragment.kt -- This file is part of tiny_container.
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
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4DialogQuickNodeEditBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 快捷方式节点的添加/编辑对话框。
 *
 * 模式：
 * - "add_folder"：仅显示名称+简介，type 固定为 folder 类型
 * - "add_command"：显示名称+简介+命令
 * - "add_option"：显示所有 option 字段
 * - "edit"：根据已有 type 显示对应字段，预填值
 */
class QuickNodeEditDialogFragment : DialogFragment() {

    private var _binding: Tc4DialogQuickNodeEditBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = Tc4DialogQuickNodeEditBinding.inflate(requireActivity().layoutInflater)
        val args = requireArguments()
        val mode = args.getString(ARG_MODE) ?: ""
        val tab = args.getString(ARG_TAB) ?: "commands"

        setupFields(mode, tab, savedInstanceState)

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setNegativeButton(R.string.tc4_btn_cancel, null)
            .setPositiveButton(R.string.tc4_btn_ok, null)
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
        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = binding.nameInput.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                binding.nameLayout.error = getString(R.string.tc4_validate_name_empty)
                return@setOnClickListener
            }
            binding.nameLayout.error = null
            sendResult()
            dismiss()
        }
    }

    private fun setupFields(mode: String, tab: String, savedInstanceState: Bundle?) {
        val args = requireArguments()

        when (mode) {
            "add_folder" -> {
                binding.optionFields.visibility = View.GONE
                binding.commandLayout.visibility = View.GONE
            }
            "add_command" -> {
                binding.optionFields.visibility = View.GONE
                binding.commandLayout.visibility = View.VISIBLE
            }
            "add_option" -> {
                binding.commandLayout.visibility = View.GONE
                binding.optionFields.visibility = View.VISIBLE
            }
            "edit" -> {
                val type = args.getString(ARG_TYPE) ?: ""
                when (type) {
                    "command" -> {
                        binding.optionFields.visibility = View.GONE
                        binding.commandLayout.visibility = View.VISIBLE
                    }
                    "commands", "options" -> {
                        binding.optionFields.visibility = View.GONE
                        binding.commandLayout.visibility = View.GONE
                    }
                    "option" -> {
                        binding.commandLayout.visibility = View.GONE
                        binding.optionFields.visibility = View.VISIBLE
                    }
                }
            }
        }

        // 填入初始值（优先从 savedInstanceState 恢复）
        binding.nameInput.setText(
            savedInstanceState?.getString(SAVED_NAME) ?: args.getString(ARG_NAME)
        )
        binding.descInput.setText(
            savedInstanceState?.getString(SAVED_DESC) ?: args.getString(ARG_DESC)
        )
        binding.cmdInput.setText(
            savedInstanceState?.getString(SAVED_CMD) ?: args.getString(ARG_CMD)
        )

        if (binding.optionFields.visibility == View.VISIBLE) {
            binding.envInput.setText(
                savedInstanceState?.getString(SAVED_ENV) ?: args.getString(ARG_ENV)
            )
            binding.pathInput.setText(
                savedInstanceState?.getString(SAVED_PATH) ?: args.getString(ARG_PATH)
            )
            binding.ldPathInput.setText(
                savedInstanceState?.getString(SAVED_LD_PATH) ?: args.getString(ARG_LD_PATH)
            )
            binding.ldPreloadInput.setText(
                savedInstanceState?.getString(SAVED_LD_PRELOAD) ?: args.getString(ARG_LD_PRELOAD)
            )
            binding.argsInput.setText(
                savedInstanceState?.getString(SAVED_ARGS) ?: args.getString(ARG_ARGS)
            )
            binding.preHostInput.setText(
                savedInstanceState?.getString(SAVED_PRE_HOST) ?: args.getString(ARG_PRE_HOST)
            )
            binding.postContainerInput.setText(
                savedInstanceState?.getString(SAVED_POST_CONTAINER) ?: args.getString(ARG_POST_CONTAINER)
            )
            binding.postEndHostInput.setText(
                savedInstanceState?.getString(SAVED_POST_END_HOST) ?: args.getString(ARG_POST_END_HOST)
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVED_NAME, binding.nameInput.text?.toString() ?: "")
        outState.putString(SAVED_DESC, binding.descInput.text?.toString() ?: "")
        outState.putString(SAVED_CMD, binding.cmdInput.text?.toString() ?: "")
        if (binding.optionFields.visibility == View.VISIBLE) {
            outState.putString(SAVED_ENV, binding.envInput.text?.toString() ?: "")
            outState.putString(SAVED_PATH, binding.pathInput.text?.toString() ?: "")
            outState.putString(SAVED_LD_PATH, binding.ldPathInput.text?.toString() ?: "")
            outState.putString(SAVED_LD_PRELOAD, binding.ldPreloadInput.text?.toString() ?: "")
            outState.putString(SAVED_ARGS, binding.argsInput.text?.toString() ?: "")
            outState.putString(SAVED_PRE_HOST, binding.preHostInput.text?.toString() ?: "")
            outState.putString(SAVED_POST_CONTAINER, binding.postContainerInput.text?.toString() ?: "")
            outState.putString(SAVED_POST_END_HOST, binding.postEndHostInput.text?.toString() ?: "")
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
    }

    private fun sendResult() {
        val args = requireArguments()
        val mode = args.getString(ARG_MODE) ?: ""
        val bundle = Bundle().apply {
            putString(KEY_MODE, mode)
            putInt(KEY_INDEX, args.getInt(ARG_INDEX, -1))
            putString(KEY_TAB, args.getString(ARG_TAB))
            putString(KEY_TYPE, args.getString(ARG_TYPE))

            // name 必填（已在 onStart 校验）
            putString(KEY_NAME, binding.nameInput.text?.toString()?.trim() ?: "")

            val desc = binding.descInput.text?.toString()?.trim() ?: ""
            if (desc.isNotEmpty()) putString(KEY_DESC, desc)

            if (binding.commandLayout.visibility == View.VISIBLE) {
                val cmd = binding.cmdInput.text?.toString()?.trim() ?: ""
                if (cmd.isNotEmpty()) putString(KEY_CMD, cmd)
            }

            if (binding.optionFields.visibility == View.VISIBLE) {
                val env = parseLines(binding.envInput.text?.toString())
                if (env.isNotEmpty()) putStringArrayList(KEY_ENV, ArrayList(env))

                val path = parseLines(binding.pathInput.text?.toString())
                if (path.isNotEmpty()) putStringArrayList(KEY_PATH, ArrayList(path))

                val ldPath = parseLines(binding.ldPathInput.text?.toString())
                if (ldPath.isNotEmpty()) putStringArrayList(KEY_LD_PATH, ArrayList(ldPath))

                val ldPreload = parseLines(binding.ldPreloadInput.text?.toString())
                if (ldPreload.isNotEmpty()) putStringArrayList(KEY_LD_PRELOAD, ArrayList(ldPreload))

                val argz = parseLines(binding.argsInput.text?.toString())
                if (argz.isNotEmpty()) putStringArrayList(KEY_ARGS, ArrayList(argz))

                val preHost = binding.preHostInput.text?.toString()?.trim() ?: ""
                if (preHost.isNotEmpty()) putString(KEY_PRE_HOST, preHost)

                val postContainer = binding.postContainerInput.text?.toString()?.trim() ?: ""
                if (postContainer.isNotEmpty()) putString(KEY_POST_CONTAINER, postContainer)

                val postEndHost = binding.postEndHostInput.text?.toString()?.trim() ?: ""
                if (postEndHost.isNotEmpty()) putString(KEY_POST_END_HOST, postEndHost)
            }
        }
        parentFragmentManager.setFragmentResult(REQUEST_SAVE, bundle)
    }

    private fun parseLines(text: String?): List<String> {
        return text?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_SAVE = "quick_node_save"

        const val KEY_MODE = "mode"
        const val KEY_INDEX = "index"
        const val KEY_TAB = "tab"
        const val KEY_TYPE = "type"
        const val KEY_NAME = "name"
        const val KEY_DESC = "description"
        const val KEY_CMD = "command"
        const val KEY_ENV = "env"
        const val KEY_PATH = "path"
        const val KEY_LD_PATH = "ld_library_path"
        const val KEY_LD_PRELOAD = "ld_preload"
        const val KEY_ARGS = "args"
        const val KEY_PRE_HOST = "pre_start_host_command"
        const val KEY_POST_CONTAINER = "post_start_container_command"
        const val KEY_POST_END_HOST = "post_end_host_command"

        private const val ARG_MODE = "arg_mode"
        private const val ARG_INDEX = "arg_index"
        private const val ARG_TAB = "arg_tab"
        private const val ARG_TYPE = "arg_type"
        private const val ARG_NAME = "arg_name"
        private const val ARG_DESC = "arg_desc"
        private const val ARG_CMD = "arg_cmd"
        private const val ARG_ENV = "arg_env"
        private const val ARG_PATH = "arg_path"
        private const val ARG_LD_PATH = "arg_ld_path"
        private const val ARG_LD_PRELOAD = "arg_ld_preload"
        private const val ARG_ARGS = "arg_args"
        private const val ARG_PRE_HOST = "arg_pre_host"
        private const val ARG_POST_CONTAINER = "arg_post_container"
        private const val ARG_POST_END_HOST = "arg_post_end_host"

        private const val SAVED_NAME = "saved_name"
        private const val SAVED_DESC = "saved_desc"
        private const val SAVED_CMD = "saved_cmd"
        private const val SAVED_ENV = "saved_env"
        private const val SAVED_PATH = "saved_path"
        private const val SAVED_LD_PATH = "saved_ld_path"
        private const val SAVED_LD_PRELOAD = "saved_ld_preload"
        private const val SAVED_ARGS = "saved_args"
        private const val SAVED_PRE_HOST = "saved_pre_host"
        private const val SAVED_POST_CONTAINER = "saved_post_container"
        private const val SAVED_POST_END_HOST = "saved_post_end_host"

        // ========== 工厂方法 ==========

        /** 添加文件夹 */
        fun newAddFolder(tab: String) = QuickNodeEditDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, "add_folder")
                putString(ARG_TAB, tab)
                val folderType = if (tab == "commands") "commands" else "options"
                putString(ARG_TYPE, folderType)
                putString(ARG_NAME, "")
                putString(ARG_DESC, "")
            }
        }

        /** 添加叶子节点（command 或 option） */
        fun newAddLeaf(tab: String) = QuickNodeEditDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, if (tab == "commands") "add_command" else "add_option")
                putString(ARG_TAB, tab)
                val leafType = if (tab == "commands") "command" else "option"
                putString(ARG_TYPE, leafType)
                putString(ARG_NAME, "")
                putString(ARG_DESC, "")
                putString(ARG_CMD, "")
                putString(ARG_ENV, "")
                putString(ARG_PATH, "")
                putString(ARG_LD_PATH, "")
                putString(ARG_LD_PRELOAD, "")
                putString(ARG_ARGS, "")
                putString(ARG_PRE_HOST, "")
                putString(ARG_POST_CONTAINER, "")
                putString(ARG_POST_END_HOST, "")
            }
        }

        /** 编辑已有节点 */
        fun newEdit(tab: String, index: Int, type: String, data: Map<String, Any>) =
            QuickNodeEditDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, "edit")
                    putString(ARG_TAB, tab)
                    putInt(ARG_INDEX, index)
                    putString(ARG_TYPE, type)
                    putString(ARG_NAME, data["name"] as? String ?: "")
                    putString(ARG_DESC, data["description"] as? String ?: "")
                    putString(ARG_CMD, data["command"] as? String ?: "")
                    putString(ARG_ENV, (data["env"] as? List<*>)?.joinToString("\n") ?: "")
                    putString(ARG_PATH, (data["path"] as? List<*>)?.joinToString("\n") ?: "")
                    putString(ARG_LD_PATH, (data["ld_library_path"] as? List<*>)?.joinToString("\n") ?: "")
                    putString(ARG_LD_PRELOAD, (data["ld_preload"] as? List<*>)?.joinToString("\n") ?: "")
                    putString(ARG_ARGS, (data["args"] as? List<*>)?.joinToString("\n") ?: "")
                    putString(ARG_PRE_HOST, data["pre_start_host_command"] as? String ?: "")
                    putString(ARG_POST_CONTAINER, data["post_start_container_command"] as? String ?: "")
                    putString(ARG_POST_END_HOST, data["post_end_host_command"] as? String ?: "")
                }
            }
    }
}
