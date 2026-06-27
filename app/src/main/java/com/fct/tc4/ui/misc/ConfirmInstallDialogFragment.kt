// ConfirmInstallDialogFragment.kt -- This file is part of tiny_container.
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

import android.app.Application
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4DialogConfirmInstallBinding
import com.fct.tc4.ui.page.ContainerManageViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 确认安装容器对话框。
 * 通过 activityViewModels 将编辑状态保存在 ViewModel 中，旋转不丢失。
 */
class ConfirmInstallDialogFragment : DialogFragment() {

    private var _binding: Tc4DialogConfirmInstallBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ContainerManageViewModel by activityViewModels()
    private var requestKey: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = Tc4DialogConfirmInstallBinding.inflate(requireActivity().layoutInflater)
        val args = requireArguments()
        requestKey = args.getString(ARG_REQUEST_KEY)!!

        val code = viewModel.installDialogCode.value.ifEmpty { args.getString(ARG_INITIAL_CODE) ?: "" }
        binding.codeInput.setText(code)
        binding.nameText.text = args.getString(ARG_NAME)
        binding.descriptionText.text = args.getString(ARG_DESCRIPTION)
        binding.launchSwitch.isChecked = viewModel.installDialogLaunch.value

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setNegativeButton(R.string.tc4_btn_cancel) { _, _ ->
                viewModel.resetInstallDialogState()
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putBoolean(KEY_CONFIRMED, false) })
            }
            .setPositiveButton(R.string.tc4_btn_confirm_install, null)
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
        val positiveBtn = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val args = requireArguments()
        val dataDir = (requireContext().applicationContext as Application).dataDir.absolutePath
        val installedCodes = Global.installedContainers

        val savedCode = viewModel.installDialogCode.value.ifEmpty { args.getString(ARG_INITIAL_CODE) ?: "" }
        binding.codeInput.setText(savedCode)
        binding.launchSwitch.isChecked = viewModel.installDialogLaunch.value

        fun updateWarningsAndButton(code: String) {
            val error = validateCode(code)
            binding.codeLayout.error = error
            positiveBtn.isEnabled = error == null
            binding.warningDataDir.text = getString(R.string.tc4_install_warning_data, "$dataDir/$code")
            if (code in installedCodes) {
                binding.warningOverwrite.text = getString(R.string.tc4_install_warning_overwrite, code)
                binding.warningOverwrite.visibility = View.VISIBLE
            } else {
                binding.warningOverwrite.visibility = View.GONE
            }
        }

        updateWarningsAndButton(binding.codeInput.text?.toString() ?: "")

        binding.codeInput.doAfterTextChanged { text ->
            val current = text?.toString() ?: ""
            viewModel.updateInstallDialogCode(current)
            updateWarningsAndButton(current)
        }

        binding.launchSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateInstallDialogLaunch(isChecked)
        }

        positiveBtn.setOnClickListener {
            val code = binding.codeInput.text?.toString() ?: ""
            val error = validateCode(code)
            if (error != null) {
                binding.codeLayout.error = error
                return@setOnClickListener
            }
            viewModel.resetInstallDialogState()
            parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                putBoolean(KEY_CONFIRMED, true)
                putString(KEY_CODE, code)
                putBoolean(KEY_LAUNCH, binding.launchSwitch.isChecked)
            })
            dismiss()
        }
    }

    private fun validateCode(code: String): String? {
        if (code.length !in 1..32) return getString(R.string.tc4_validate_code_length)
        if (!code.matches(Regex("^[a-zA-Z0-9]+$"))) return getString(R.string.tc4_validate_code_chars)
        val denylist = resources.getStringArray(com.fct.tc4.R.array.tc4_container_code_denylist)
        if (denylist.any { pattern -> Regex(pattern, RegexOption.IGNORE_CASE).matches(code) }) {
            return getString(R.string.tc4_validate_code_reserved)
        }
        return null
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        viewModel.resetInstallDialogState()
        parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putBoolean(KEY_CONFIRMED, false) })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_INITIAL_CODE = "initial_code"
        private const val ARG_NAME = "name"
        private const val ARG_DESCRIPTION = "description"

        const val KEY_CONFIRMED = "confirmed"
        const val KEY_CODE = "code"
        const val KEY_LAUNCH = "launch"

        fun show(
            fragmentManager: FragmentManager,
            requestKey: String,
            initialCode: String,
            name: String,
            description: String
        ) {
            ConfirmInstallDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putString(ARG_INITIAL_CODE, initialCode)
                    putString(ARG_NAME, name)
                    putString(ARG_DESCRIPTION, description)
                }
            }.show(fragmentManager, "confirm_install_$requestKey")
        }
    }
}
