// ContainerEditDialogFragment.kt -- This file is part of tiny_container.
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
import com.fct.tc4.databinding.Tc4DialogContainerEditBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ContainerEditDialogFragment : DialogFragment() {

    private var _binding: Tc4DialogContainerEditBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = Tc4DialogContainerEditBinding.inflate(requireActivity().layoutInflater)
        val args = requireArguments()

        // 填入初始值（优先从 savedInstanceState 恢复，确保旋转不丢失输入）
        binding.nameInput.setText(
            savedInstanceState?.getString(SAVED_NAME) ?: args.getString(ARG_NAME)
        )
        binding.descInput.setText(
            savedInstanceState?.getString(SAVED_DESC) ?: args.getString(ARG_DESC)
        )

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tc4_dialog_edit_container_title)
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
            sendResult()
            dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVED_NAME, binding.nameInput.text?.toString() ?: "")
        outState.putString(SAVED_DESC, binding.descInput.text?.toString() ?: "")
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // 点击外部区域取消不做任何保存
    }

    private fun sendResult() {
        val args = requireArguments()
        parentFragmentManager.setFragmentResult(REQUEST_SAVE, Bundle().apply {
            putString(KEY_CODE, args.getString(ARG_CODE))
            putString(KEY_NAME, binding.nameInput.text?.toString() ?: "")
            putString(KEY_DESC, binding.descInput.text?.toString() ?: "")
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_SAVE = "container_edit_save"

        const val KEY_CODE = "code"
        const val KEY_NAME = "name"
        const val KEY_DESC = "description"

        private const val ARG_CODE = "arg_code"
        private const val ARG_NAME = "arg_name"
        private const val ARG_DESC = "arg_desc"

        private const val SAVED_NAME = "saved_name"
        private const val SAVED_DESC = "saved_desc"

        fun newInstance(
            code: String,
            name: String,
            description: String
        ) = ContainerEditDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_CODE, code)
                putString(ARG_NAME, name)
                putString(ARG_DESC, description)
            }
        }
    }
}
