// HelpFragment.kt -- This file is part of tiny_container.
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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4FragmentHelpBinding
import com.fct.tc4.databinding.Tc4HelpFooterItemBinding
import com.fct.tc4.databinding.Tc4SettingsActionItemBinding
import com.google.android.material.listitem.ListItemViewHolder

class HelpFragment : Fragment() {

    private var _binding: Tc4FragmentHelpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        _binding = Tc4FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        val items = buildHelpItems()
        val footer = HelpFooter(
            icon = context.packageManager.getApplicationIcon(context.packageName),
            name = context.applicationInfo.loadLabel(context.packageManager).toString(),
            version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "(^-^*)"
        )

        val adapter = HelpAdapter(items, footer) { item ->
            when (item.titleRes) {
                R.string.tc4_help_oss_title ->
                    startActivity(Intent(requireContext(), OpenSourceLibsActivity::class.java))
                R.string.tc4_help_license_title ->
                    startActivity(Intent(requireContext(), LicenseActivity::class.java))
                else -> item.url?.let {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                }
            }
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun buildHelpItems(): List<HelpItem> = listOf(
        HelpItem(
            titleRes = R.string.tc4_help_source_code_title,
            descriptionRes = R.string.tc4_help_source_code_desc,
            url = "https://github.com/Cateners/tiny_container"
        ),
        HelpItem(
            titleRes = R.string.tc4_help_releases_title,
            descriptionRes = R.string.tc4_help_releases_desc,
            url = "https://github.com/Cateners/tiny_container/releases"
        ),
        HelpItem(
            titleRes = R.string.tc4_help_oss_title,
            descriptionRes = R.string.tc4_help_oss_desc,
            url = null
        ),
        HelpItem(
            titleRes = R.string.tc4_help_license_title,
            descriptionRes = R.string.tc4_help_license_desc,
            url = null
        ),
        HelpItem(
            titleRes = R.string.tc4_help_wechat_title,
            descriptionRes = R.string.tc4_help_wechat_desc,
            url = null
        ),
        HelpItem(
            titleRes = R.string.tc4_help_bilibili_title,
            descriptionRes = R.string.tc4_help_bilibili_desc,
            url = "https://space.bilibili.com/189835375"
        ),
        HelpItem(
            titleRes = R.string.tc4_help_spark_store_title,
            descriptionRes = R.string.tc4_help_spark_store_desc,
            url = "https://www.spark-app.store/"
        ),
        HelpItem(
            titleRes = R.string.tc4_help_gxde_title,
            descriptionRes = R.string.tc4_help_gxde_desc,
            url = "https://www.gxde.top/"
        ),
    )
}

// ===================== Data Model =====================

private data class HelpItem(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val url: String?
)

private data class HelpFooter(
    val icon: android.graphics.drawable.Drawable,
    val name: String,
    val version: String
)

// ===================== Adapter =====================

private class HelpAdapter(
    private val items: List<HelpItem>,
    private val footer: HelpFooter,
    private val onClick: (HelpItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ACTION = 0
        private const val TYPE_FOOTER = 1
    }

    private val actionCount = items.size

    override fun getItemViewType(position: Int): Int =
        if (position < actionCount) TYPE_ACTION else TYPE_FOOTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ACTION -> {
                val b = Tc4SettingsActionItemBinding.inflate(inflater, parent, false)
                HelpActionVH(b, onClick)
            }
            TYPE_FOOTER -> {
                val b = Tc4HelpFooterItemBinding.inflate(inflater, parent, false)
                HelpFooterVH(b, footer)
            }
            else -> error("unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val itemCount = actionCount
        when (holder) {
            is HelpActionVH -> holder.bind(items[position], position, itemCount)
            is HelpFooterVH -> {} // footer 数据已在构造函数注入
        }
    }

    override fun getItemCount() = actionCount + 1
}

// ===================== ViewHolders =====================

private class HelpActionVH(
    private val binding: Tc4SettingsActionItemBinding,
    private val onClick: (HelpItem) -> Unit
) : ListItemViewHolder(binding.root) {

    fun bind(item: HelpItem, position: Int, itemCount: Int) {
        super.bind(position, itemCount)
        val ctx = itemView.context
        binding.itemTitle.text = ctx.getString(item.titleRes)
        binding.itemDescription.text = ctx.getString(item.descriptionRes)
        binding.itemCard.setOnClickListener {
            onClick(item)
        }
    }
}

private class HelpFooterVH(
    binding: Tc4HelpFooterItemBinding,
    footer: HelpFooter
) : RecyclerView.ViewHolder(binding.root) {

    init {
        binding.footerIcon.setImageDrawable(footer.icon)
        binding.footerName.text = footer.name
        binding.footerVersion.text = binding.root.context.getString(R.string.tc4_label_version, footer.version)
    }
}
