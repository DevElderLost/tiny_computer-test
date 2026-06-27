// TerminalFragment.kt -- This file is part of tiny_container.
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

import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4FragmentTerminalBinding
import com.fct.tc4.ui.misc.Global
import com.google.android.material.snackbar.Snackbar
import com.offsec.nhterm.backend.TextStyle
import com.offsec.nhterm.frontend.session.terminal.BasicViewClient
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.launch

class TerminalFragment : Fragment() {

    private var _binding: Tc4FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = Tc4FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Global.terminalSession == null) {
            Snackbar.make(binding.root, R.string.tc4_terminal_no_session, Snackbar.LENGTH_SHORT).show()
            binding.main.visibility = View.INVISIBLE
            return
        }

        binding.terminalView.setTextSize(Global.terminalFontSize)
        binding.terminalView.attachSession(Global.terminalSession)

        val ta = requireContext().theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        val fgColor = ta.getColor(0, Color.WHITE)
        ta.recycle()

        val emulator = Global.terminalSession?.emulator
        emulator?.mColors?.mCurrentColors?.let { colors ->
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = fgColor
        }
        binding.terminalView.onScreenUpdated()

        val extraKeys = binding.extraKeys
        extraKeys.setTextColor(fgColor)
        binding.terminalView.setTerminalViewClient(object : BasicViewClient(binding.terminalView) {
            override fun onScale(scale: Float): Float {
                val result = super.onScale(scale)
                Global.terminalFontSize = binding.terminalView.textSize
                return result
            }

            override fun readControlKey(): Boolean = extraKeys.readControlButton()
            override fun readAltKey(): Boolean = extraKeys.readAltButton()
            override fun readShiftKey(): Boolean = extraKeys.readShiftButton()
            override fun readFnKey(): Boolean = extraKeys.readFnButton()
        })

        lifecycleScope.launch {
            Global.screenUpdateEvent.collect {
                binding.terminalView.onScreenUpdated()
            }
        }

        extraKeys.attachTerminalView(binding.terminalView)
        extraKeys.onToggleIme = {
            val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(0, 0)
        }

        binding.terminalView.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun getBlurView(): BlurView? {
        return _binding?.blurView
    }
}
