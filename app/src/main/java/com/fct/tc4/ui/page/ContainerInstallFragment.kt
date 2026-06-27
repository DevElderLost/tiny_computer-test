// ContainerInstallFragment.kt -- This file is part of tiny_container.
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
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4FragmentContainerInstallBinding
import com.fct.tc4.ui.main.MainViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class ContainerInstallFragment : Fragment() {
    private var _binding: Tc4FragmentContainerInstallBinding? = null
    private val binding get() = _binding!!
    private val containerViewModel: ContainerManageViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private var progressJob: Job? = null

    /** API 28-29: 运行时请求 WRITE_EXTERNAL_STORAGE */
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Snackbar.make(binding.root, R.string.tc4_permission_file_granted, Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, R.string.tc4_permission_file_denied, Snackbar.LENGTH_SHORT).show()
        }
    }

    /** 由 Activity 菜单栏调用，申请文件访问权限 */
    fun onFileAccessAction() {
        if (!isAdded) return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                )
            }
            else -> {
                requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    /** 由 Activity 菜单栏调用，关闭电池优化 */
    fun onBatteryAction() {
        if (!isAdded) return
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${requireContext().packageName}".toUri()
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = Tc4FragmentContainerInstallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 加载网页
        val webpage = arguments?.getString("webpage")
        if (!webpage.isNullOrBlank()) {
            binding.webpage.apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    domStorageEnabled = true
                    blockNetworkImage = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val newUrl = request?.url ?: return false
                        val originalHost = webpage.toUri().host
                        val newHost = newUrl.host
                        // 外部链接（host 不同）→ 用系统浏览器打开
                        if (newHost != null && newHost != originalHost) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, newUrl))
                            } catch (_: Exception) {
                                // 无浏览器时静默失败
                            }
                            return true
                        }
                        return false
                    }
                }
                if (url == null || url.isNullOrBlank() || url == "about:blank") {
                    loadUrl(webpage)
                }
            }
        } else {
            binding.webpage.visibility = View.GONE
        }

        // 安装页面不可返回：拦截返回键，防止退出软件
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (mainViewModel.isTerminalOpen.value) {
                mainViewModel.closeTerminal()
            }
            // 始终消费事件，不传递给 Activity 的默认回调
        }

        // 观察安装状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                containerViewModel.installState.collect { state ->
                    when (state) {
                        is InstallState.Installing -> {
                            val stepText = when (state.currentStep) {
                                InstallStep.DELETING_OLD -> getString(R.string.tc4_import_deleting_old_title)
                                InstallStep.EXTRACTING_ROOTFS -> getString(R.string.tc4_import_installing_title)
                                InstallStep.CLEANING_CACHE -> getString(R.string.tc4_import_cleaning_title)
                            }
                            requireActivity().title = stepText

                            // 假进度（仅首次进入 Installing 时启动，旋转重建后重新启动）
                            if (progressJob?.isActive != true) {
                                progressJob = lifecycleScope.launch {
                                    while (isActive) {
                                        val p = containerViewModel.fakeProgress(
                                            state.startTimeMillis, state.containerSizeBytes
                                        )
                                        binding.progressIndicator.setProgressCompat((p * 100).toInt(), true)
                                        delay(200)
                                    }
                                }
                            }
                        }
                        is InstallState.Completed -> {
                            progressJob?.cancel()
                            requireActivity().title = getString(R.string.tc4_import_completed_title)
                            containerViewModel.resetInstallState()
                            if (state.launchAfterInstall) {
                                mainViewModel.navigateTo(MainViewModel.Screen.ContainerMain(code = state.code))
                            } else {
                                mainViewModel.navigateTo(MainViewModel.Screen.ContainerManage)
                            }
                        }
                        is InstallState.Failed -> {
                            progressJob?.cancel()
                            requireActivity().title = getString(R.string.tc4_import_failed_title)
                            containerViewModel.resetInstallState()
                            mainViewModel.navigateTo(MainViewModel.Screen.ContainerManage)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        progressJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
