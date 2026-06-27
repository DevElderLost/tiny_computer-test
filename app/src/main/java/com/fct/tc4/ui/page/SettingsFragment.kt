// SettingsFragment.kt -- This file is part of tiny_container.
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

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.annotation.StringRes
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4FragmentSettingsBinding
import com.fct.tc4.databinding.Tc4SettingsActionItemBinding
import com.fct.tc4.databinding.Tc4SettingsSwitchItemBinding
import com.fct.tc4.ui.misc.LauncherShortcutDialogFragment
import com.google.android.material.listitem.ListItemViewHolder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: Tc4FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            requireActivity().application,
            arguments?.getString("code") ?: ""
        )
    }

    private lateinit var adapter: SettingsListAdapter

    /** API 28-29: 运行时请求 WRITE_EXTERNAL_STORAGE */
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Snackbar.make(binding.root, getString(R.string.tc4_permission_file_granted), Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, getString(R.string.tc4_permission_file_denied), Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        _binding = Tc4FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SettingsListAdapter(
            onToggle = { item -> viewModel.toggle(item) },
            onAction = { item -> handleAction(item) }
        )
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { items ->
                adapter.submitList(items)
            }
        }
    }

    private fun handleAction(item: ActionSetting) {
        when (item.id) {
            "launcher_shortcut" -> {
                val code = arguments?.getString("code") ?: ""
                LauncherShortcutDialogFragment.newInstance(code)
                    .show(childFragmentManager, "launcher_shortcut")
            }
            "manage_files" -> {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        // Android 11+: 跳转所有文件管理权限设置页
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${requireContext().packageName}")
                            }
                        )
                    }
                    else -> {
                        // Android 9-10: 运行时请求 WRITE_EXTERNAL_STORAGE
                        // API 29 + requestLegacyExternalStorage 可获得完整读写权限
                        requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            }
            "battery_unlimited" -> {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ========== Adapter ==========

private class SettingsListAdapter(
    private val onToggle: (SwitchSetting) -> Unit,
    private val onAction: (ActionSetting) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SWITCH = 0
        private const val TYPE_ACTION = 1
    }

    private var items: List<SettingsItem> = emptyList()

    fun submitList(newItems: List<SettingsItem>) {
        val oldItems = items
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(old: Int, new: Int) =
                oldItems[old].id == newItems[new].id
            override fun areContentsTheSame(old: Int, new: Int) =
                oldItems[old] == newItems[new]
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SwitchSetting -> TYPE_SWITCH
        is ActionSetting -> TYPE_ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SWITCH -> {
                val b = Tc4SettingsSwitchItemBinding.inflate(inflater, parent, false)
                SwitchVH(b, onToggle)
            }
            TYPE_ACTION -> {
                val b = Tc4SettingsActionItemBinding.inflate(inflater, parent, false)
                ActionVH(b, onAction)
            }
            else -> error("unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val itemCount = itemCount
        when (holder) {
            is SwitchVH -> holder.bind(item as SwitchSetting, position, itemCount)
            is ActionVH -> holder.bind(item as ActionSetting, position, itemCount)
        }
    }

    override fun getItemCount() = items.size
}

// ========== ViewHolders ==========

private class SwitchVH(
    private val binding: Tc4SettingsSwitchItemBinding,
    private val onToggle: (SwitchSetting) -> Unit
) : ListItemViewHolder(binding.root) {

    fun bind(item: SwitchSetting, position: Int, itemCount: Int) {
        super.bind(position, itemCount)
        val ctx = itemView.context
        binding.itemTitle.text = ctx.getString(item.titleRes)
        binding.itemDescription.text = ctx.getString(item.descriptionRes)

        binding.itemSwitch.setOnCheckedChangeListener(null)
        binding.itemSwitch.isChecked = item.isChecked
        binding.itemSwitch.setOnCheckedChangeListener { _, _ ->
            onToggle(item)
        }
        binding.itemCard.setOnClickListener {
            binding.itemSwitch.toggle()
        }
    }
}

private class ActionVH(
    private val binding: Tc4SettingsActionItemBinding,
    private val onAction: (ActionSetting) -> Unit
) : ListItemViewHolder(binding.root) {

    fun bind(item: ActionSetting, position: Int, itemCount: Int) {
        super.bind(position, itemCount)
        val ctx = itemView.context
        binding.itemTitle.text = ctx.getString(item.titleRes)
        binding.itemDescription.text = ctx.getString(item.descriptionRes)
        binding.itemCard.setOnClickListener {
            onAction(item)
        }
    }
}
