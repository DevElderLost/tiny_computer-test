// ConfigManager.kt -- This file is part of tiny_container.
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

import android.content.Context
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 容器配置管理器
 *
 * 每个已安装的容器对应 datadir/<code>/config.yaml。
 * 支持通过点号分隔的键路径（如 "feature.1.type"、"options.0.name"）来读写值。
 */
object ConfigManager {

    private lateinit var dataDir: File
    private val yaml = Yaml()

    fun init(context: Context) {
        dataDir = context.dataDir
    }

    // ==================== 文件操作 ====================

    private fun configFile(code: String): File =
        File(dataDir, "$code/.tiny.yaml")

    /**
     * 加载某个容器的完整配置，返回 YAML 解析后的 Map（支持嵌套 List/Map）。
     * 如果文件不存在返回 null。
     */
    fun load(code: String): Map<String, Any>? {
        val file = configFile(code)
        if (!file.exists()) return null
        @Suppress("UNCHECKED_CAST")
        return yaml.load<Map<String, Any>>(file.readText())
    }

    /**
     * 将 Map 写回 yaml。
     */
    fun save(code: String, config: Map<String, Any>) {
        val dir = configFile(code).parentFile!!
        dir.mkdirs()
        configFile(code).writeText(yaml.dump(config))
    }

    // ==================== 路径读写 ====================

    /**
     * 沿着 [keyPath] 导航到树中某个值。
     *
     * [keyPath] 为点号分隔的键名序列，如：
     *   - "name"                     → 顶层 name
     *   - "feature.0.type"          → feature[0].type
     *   - "options.0.options.1.enabled" → 嵌套 option 的 enabled
     *   - "boot_command"            → 顶层 boot_command
     *
     * 如果路径无效或中间节点不存在，返回 null。
     */
    fun get(code: String, keyPath: String): Any? {
        val config = load(code) ?: return null
        return navigate(config, keyPath)
    }

    /**
     * 沿着 [keyPath] 设置值。中间路径不存在时会自动创建 Map。
     * 设置后立即写回磁盘。
     *
     * @return true 表示成功设置，false 表示 code 不存在或写入失败
     */
    fun set(code: String, keyPath: String, value: Any): Boolean {
        val file = configFile(code)
        if (!file.exists()) return false

        @Suppress("UNCHECKED_CAST")
        val root = yaml.load<MutableMap<String, Any>>(file.readText())
            ?: return false

        val keys = keyPath.split(".")
        navigateAndSet(root, keys, 0, value)

        file.writeText(yaml.dump(root))
        return true
    }

    // ==================== 路径导航 ====================

    /** 沿路径读取 */
    @Suppress("UNCHECKED_CAST")
    private fun navigate(node: Any, keyPath: String): Any? {
        val keys = keyPath.split(".")
        var current: Any? = node
        for (key in keys) {
            current = when (current) {
                is Map<*, *> -> (current as Map<String, Any>)[key]
                is List<*> -> {
                    val idx = key.toIntOrNull() ?: return null
                    (current as List<Any>).getOrNull(idx)
                }
                else -> return null
            }
            if (current == null) return null
        }
        return current
    }

    /** 沿路径写入（DFS） */
    @Suppress("UNCHECKED_CAST")
    private fun navigateAndSet(
        node: MutableMap<String, Any>,
        keys: List<String>,
        depth: Int,
        value: Any
    ) {
        val key = keys[depth]
        if (depth == keys.lastIndex) {
            node[key] = value
            return
        }

        val nextKey = keys[depth + 1]
        val nextIsIndex = nextKey.toIntOrNull()

        // 深度遍历 —— 如果中间节点不存在则创建
        val child = node[key]
        when {
            // 下一级是数字索引 → 中间应为 List
            nextIsIndex != null -> {
                val list = (child as? MutableList<Any>)
                    ?: mutableListOf<Any>().also { node[key] = it }
                // 确保 list 长度足够
                val idx = nextIsIndex
                while (list.size <= idx) list.add(mutableMapOf<String, Any>())
                val entry = list[idx] as? MutableMap<String, Any>
                    ?: mutableMapOf<String, Any>().also { list[idx] = it }
                navigateAndSet(entry, keys, depth + 1, value)
            }
            // 下一级是字符串键 → 中间应为 Map
            else -> {
                val map = (child as? MutableMap<String, Any>)
                    ?: mutableMapOf<String, Any>().also { node[key] = it }
                navigateAndSet(map, keys, depth + 1, value)
            }
        }
    }
}
