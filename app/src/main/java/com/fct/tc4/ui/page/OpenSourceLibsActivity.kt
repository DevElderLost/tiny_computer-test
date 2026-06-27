// OpenSourceLibsActivity.kt -- This file is part of tiny_container.
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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fct.tc4.databinding.Tc4ActivityOpenSourceLibsBinding
import com.fct.tc4.databinding.Tc4ItemOpenSourceLibBinding
import com.google.android.material.listitem.ListItemViewHolder
import org.json.JSONArray
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class OpenSourceLibsActivity : AppCompatActivity() {

    private lateinit var binding: Tc4ActivityOpenSourceLibsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = Tc4ActivityOpenSourceLibsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val items = loadLibraries()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.recyclerView.adapter = LibAdapter(items) { lib ->
            startActivity(Intent(Intent.ACTION_VIEW, lib.url.toUri()))
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun loadLibraries(): List<OpenSourceLib> {
        return try {
            val json = assets.open("open_source_libraries.json")
                .bufferedReader()
                .use { it.readText() }
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                OpenSourceLib(
                    name = obj.getString("name"),
                    author = obj.optString("author", ""),
                    description = obj.optString("description", ""),
                    license = obj.getString("license"),
                    url = obj.optString("url", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

// ─── Data ─────────────────────────────────────────────────

private data class OpenSourceLib(
    val name: String,
    val author: String,
    val description: String,
    val license: String,
    val url: String
)

// ─── Adapter & VH ────────────────────────────────────────

private class LibAdapter(
    private val items: List<OpenSourceLib>,
    private val onClick: (OpenSourceLib) -> Unit
) : RecyclerView.Adapter<LibVH>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): LibVH {
        val b = Tc4ItemOpenSourceLibBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return LibVH(b, onClick)
    }

    override fun onBindViewHolder(holder: LibVH, position: Int) {
        holder.bind(items[position], position, itemCount)
    }

    override fun getItemCount() = items.size
}

private class LibVH(
    private val binding: Tc4ItemOpenSourceLibBinding,
    private val onClick: (OpenSourceLib) -> Unit
) : ListItemViewHolder(binding.root) {

    fun bind(item: OpenSourceLib, position: Int, itemCount: Int) {
        super.bind(position, itemCount)
        binding.itemTitle.text = item.name
        binding.itemDescription.text = item.description
        binding.itemLicense.text = item.license
        binding.itemCard.setOnClickListener {
            onClick(item)
        }
    }
}
