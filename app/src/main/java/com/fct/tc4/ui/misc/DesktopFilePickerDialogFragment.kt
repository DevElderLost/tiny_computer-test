// DesktopFilePickerDialogFragment.kt -- This file is part of tiny_container.
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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.fct.tc4.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * .desktop 文件选择器对话框（DialogFragment，旋转安全）。
 *
 * 选择结果通过 Fragment Result API ([parentFragmentManager.setFragmentResult]) 发送给父对话框。
 *
 * 旋转策略：扫描结果存入 onSaveInstanceState，重建时优先恢复。
 */
class DesktopFilePickerDialogFragment : DialogFragment() {

    companion object {
        const val REQUEST_DESKTOP_PICK = "desktop_pick"
        const val KEY_NAME = "pick_name"
        const val KEY_CMD = "pick_cmd"
        const val KEY_ICON = "pick_icon"

        private const val ARG_CODE = "arg_code"
        private const val SAVED_NAMES = "saved_names"
        private const val SAVED_FILES = "saved_files"
        private const val SAVED_PATHS = "saved_paths"
        private const val SAVED_ICONS = "saved_icons"
        private const val SAVED_CMDS = "saved_cmds"

        private data class Entry(
            val name: String,
            val fileName: String,
            val path: String,
            val command: String,
            val iconPath: String? = null,
        )

        fun newInstance(code: String) = DesktopFilePickerDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_CODE, code) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val code = requireArguments().getString(ARG_CODE) ?: ""

        val entries: List<Entry> = savedInstanceState?.let { restoreEntries(it) }
            ?: scanDesktopFiles(code)

        if (entries.isEmpty()) {
            return MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.tc4_btn_quick_fill)
                .setMessage(R.string.tc4_desktop_picker_empty)
                .setPositiveButton(R.string.tc4_btn_ok, null)
                .create()
        }

        val displayNames = entries.map { it.name }
        val adapter = object : ArrayAdapter<String>(
            requireContext(), android.R.layout.simple_list_item_2, android.R.id.text1, displayNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val entry = entries[position]
                view.findViewById<TextView>(android.R.id.text1).text = entry.name
                view.findViewById<TextView>(android.R.id.text2).text = entry.fileName
                return view
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tc4_desktop_picker_title)
            .setAdapter(adapter) { _, which ->
                val entry = entries[which]
                parentFragmentManager.setFragmentResult(REQUEST_DESKTOP_PICK, Bundle().apply {
                    putString(KEY_NAME, entry.name)
                    putString(KEY_CMD, entry.command)
                    putString(KEY_ICON, entry.iconPath)
                })
                dismiss()
            }
            .setNegativeButton(R.string.tc4_btn_cancel, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val entries = thisEntries
        if (entries != null) {
            outState.putStringArrayList(SAVED_NAMES, ArrayList(entries.map { it.name }))
            outState.putStringArrayList(SAVED_FILES, ArrayList(entries.map { it.fileName }))
            outState.putStringArrayList(SAVED_PATHS, ArrayList(entries.map { it.path }))
            outState.putStringArrayList(SAVED_CMDS, ArrayList(entries.map { it.command }))
            outState.putStringArrayList(SAVED_ICONS, ArrayList(entries.map { it.iconPath ?: "" }))
        }
    }

    private var thisEntries: List<Entry>? = null

    private fun restoreEntries(savedInstanceState: Bundle): List<Entry>? {
        val names = savedInstanceState.getStringArrayList(SAVED_NAMES) ?: return null
        val files = savedInstanceState.getStringArrayList(SAVED_FILES) ?: return null
        val paths = savedInstanceState.getStringArrayList(SAVED_PATHS) ?: return null
        val cmds = savedInstanceState.getStringArrayList(SAVED_CMDS) ?: return null
        val icons = savedInstanceState.getStringArrayList(SAVED_ICONS)
        if (names.size != files.size || names.size != paths.size || names.size != cmds.size) return null
        return names.indices.map { i ->
            Entry(
                name = names[i],
                fileName = files[i],
                path = paths[i],
                command = cmds[i],
                iconPath = icons?.getOrNull(i)?.ifEmpty { null },
            )
        }.also { thisEntries = it }
    }

    private fun scanDesktopFiles(code: String): List<Entry> {
        val desktopDir = File(requireContext().dataDir, "$code/usr/share/applications")
        val desktopFiles = desktopDir.listFiles { f -> f.name.endsWith(".desktop") }
            ?: return emptyList()

        val entries = desktopFiles.mapNotNull { file ->
            try {
                val lines = file.readLines()
                val name = lines.firstOrNull { it.startsWith("Name=") }
                    ?.removePrefix("Name=")?.trim() ?: return@mapNotNull null
                val exec = lines.firstOrNull { it.startsWith("Exec=") }
                    ?.removePrefix("Exec=")?.trim() ?: return@mapNotNull null
                val command = exec.replace(Regex("%[a-zA-Z]"), "").trim()
                if (command.isEmpty()) return@mapNotNull null
                val bgCommand = if (command.endsWith(" &")) command else "$command &"
                val icon = lines.firstOrNull { it.startsWith("Icon=") }
                    ?.removePrefix("Icon=")?.trim()
                val iconPath = icon?.let { resolveIconPath(code, it) }
                Entry(name, file.name, file.absolutePath, bgCommand, iconPath)
            } catch (_: Exception) { null }
        }.sortedBy { it.name }

        thisEntries = entries
        return entries
    }

    /**
     * 解析 .desktop 文件中 Icon= 字段，返回 Android 侧可读取的图标文件路径。
     * 支持：容器内绝对路径 /usr/share/...、图标名称查找（hicolor/pixmaps）。
     * 返回值可为 null（找不到可用图标文件）。
     */
    private fun resolveIconPath(code: String, iconValue: String): String? {
        val containerRoot = File(requireContext().dataDir, code)
        val value = iconValue.trim()
        if (value.isEmpty()) return null

        // 绝对路径（容器内路径，如 /usr/share/icons/...）
        if (value.startsWith("/")) {
            val androidPath = File(containerRoot, value.removePrefix("/"))
            if (androidPath.isFile) {
                val name = androidPath.name.lowercase()
                if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".svg")) {
                    return androidPath.absolutePath
                }
            }
            return null
        }

        // 图标名称：在常见目录中搜索
        val name = value
        val extensions = listOf(".png", ".jpg", ".jpeg", ".svg")
        val searchPaths = listOf(
            "/usr/share/icons/hicolor/256x256/apps",
            "/usr/share/icons/hicolor/128x128/apps",
            "/usr/share/icons/hicolor/96x96/apps",
            "/usr/share/icons/hicolor/72x72/apps",
            "/usr/share/icons/hicolor/64x64/apps",
            "/usr/share/icons/hicolor/48x48/apps",
            "/usr/share/icons/hicolor/32x32/apps",
            "/usr/share/icons/hicolor/24x24/apps",
            "/usr/share/icons/hicolor/scalable/apps",
            "/usr/share/pixmaps",
        )

        for (dir in searchPaths) {
            val baseDir = File(containerRoot, dir.removePrefix("/"))
            for (ext in extensions) {
                val file = File(baseDir, "$name$ext")
                if (file.isFile) return file.absolutePath
            }
        }

        return null
    }
}
