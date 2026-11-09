package com.example.nasbrowser

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.nasbrowser.databinding.FragmentSimpleListBinding
import java.io.File

/** Installed-app manager: list apps and export their APK into the app's Downloads folder. */
class AppsFragment : Fragment() {
    private data class AppInfo(val label: String, val pkg: String, val apkPath: String)

    private var _binding: FragmentSimpleListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ArrayAdapter<String>
    private var items = listOf<AppInfo>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimpleListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvTitle.text = getString(R.string.nav_apps)
        val pm = requireContext().packageManager
        items = try {
            pm.getInstalledPackages(0).mapNotNull { pi ->
                val ai = pi.applicationInfo ?: return@mapNotNull null
                val src = ai.sourceDir ?: return@mapNotNull null
                AppInfo(pm.getApplicationLabel(ai).toString(), pi.packageName, src)
            }.sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
        adapter = ArrayAdapter(requireContext(), R.layout.item_list_line, mutableListOf())
        binding.listView.adapter = adapter
        adapter.addAll(items.map { "${it.label}  (${it.pkg})" })
        adapter.notifyDataSetChanged()
        val empty = items.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.listView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmpty.text = getString(R.string.apps_empty)
        binding.listView.setOnItemClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { export(it) }
        }
    }

    private fun export(app: AppInfo) {
        try {
            val dir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: requireContext().filesDir
            val out = File(dir, "${app.pkg}.apk")
            File(app.apkPath).copyTo(out, true)
            Toast.makeText(
                requireContext(),
                "${getString(R.string.exported_apk)}: ${out.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
