package com.example.nasbrowser

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.nasbrowser.databinding.FragmentLanBinding
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.util.LanDevice
import com.example.nasbrowser.util.LanStore
import com.example.nasbrowser.util.PrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * LAN browser (v4): ES-style device list with a big 扫描 button. Tapping a device asks for
 * credentials and then opens a NEW SMB window/tab instead of replacing the only page.
 */
class LanFragment : Fragment(), PageActions {

    private var _binding: FragmentLanBinding? = null
    private val binding get() = _binding!!
    private var saved = listOf<LanDevice>()
    private var found = listOf<String>()
    private var rows = listOf<LanRow>()

    private data class LanRow(
        val name: String,
        val ip: String,
        val device: LanDevice?,
        val host: String
    )

    private inner class RowAdapter(ctx: Context, data: List<LanRow>) :
        ArrayAdapter<LanRow>(ctx, 0, data) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_device, parent, false)
            val r = getItem(position) ?: return v
            v.findViewById<TextView>(R.id.tvDeviceName).text = r.name
            v.findViewById<TextView>(R.id.tvDeviceIp).text = r.ip
            return v
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnScan.setOnClickListener { scan() }
        binding.btnAdd.setOnClickListener {
            showConnectDialog(localSubnet()?.removeSuffix(".") ?: "", null)
        }
        binding.listView.setOnItemClickListener { _, _, pos, _ ->
            rows.getOrNull(pos)?.let { showConnectDialog(it.host, it.device) }
        }
        binding.listView.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos)
            if (row?.device != null) {
                AlertDialog.Builder(requireContext())
                    .setTitle(row.device.host)
                    .setMessage(getString(R.string.action_delete))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        LanStore.remove(requireContext(), row.device.host)
                        refresh()
                        Toast.makeText(requireContext(), R.string.lan_removed, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else row?.let { showConnectDialog(it.host, null) }
            true
        }
        refresh()
        scan()
    }

    private fun scan() {
        if (_binding == null) return
        val subnet = localSubnet()
        if (subnet == null) {
            binding.tvSubnet.text = getString(R.string.lan_no_wifi)
            return
        }
        val cidr = "${subnet}0/24"
        binding.tvSubnet.text = getString(R.string.lan_subnet, cidr)
        binding.btnScan.isEnabled = false
        binding.tvEmpty.text = getString(R.string.lan_scanning, cidr)
        binding.tvEmpty.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                val pool = Executors.newFixedThreadPool(48)
                try {
                    val tasks = (1..254).map { i -> pool.submit(Callable { probe("$subnet$i") }) }
                    tasks.mapNotNull { f -> try { f.get() } catch (_: Exception) { null } }
                        .sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
                } finally {
                    pool.shutdownNow()
                }
            }
            if (_binding == null) return@launch
            found = hits
            binding.btnScan.isEnabled = true
            refresh()
            Toast.makeText(
                requireContext(), getString(R.string.lan_scan_done, hits.size), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun probe(ip: String): String? = try {
        Socket().use { s -> s.connect(InetSocketAddress(ip, 445), 350); ip }
    } catch (_: Exception) {
        null
    }

    /** First site-local IPv4 subnet; 192.168.x is preferred over 10.x / 172.16-31.x. */
    private fun localSubnet(): String? {
        return try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            while (en.hasMoreElements()) {
                val n = en.nextElement()
                if (!n.isUp || n.isLoopback || n.isVirtual) continue
                val addrs = n.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                    val parts = a.hostAddress?.split('.') ?: continue
                    if (parts.size != 4) continue
                    val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                    if (prefix.startsWith("192.168.")) return prefix
                    if (fallback == null) fallback = prefix
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    private fun refresh() {
        if (_binding == null) return
        saved = LanStore.list(requireContext())
        val list = mutableListOf<LanRow>()
        saved.forEach { list.add(LanRow(it.label, it.host, it, it.host)) }
        found.filter { ip -> saved.none { it.host == ip } }
            .forEach { list.add(LanRow(it, it, null, it)) }
        rows = list
        binding.listView.adapter = RowAdapter(requireContext(), rows)
        val empty = rows.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.listView.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) binding.tvEmpty.text = getString(R.string.lan_none)
    }

    private fun showConnectDialog(host: String, device: LanDevice?) {
        val ctx = requireContext()
        val prefs = PrefsStore.load(ctx)

        fun field(hintRes: Int, value: String, password: Boolean = false) = EditText(ctx).apply {
            hint = getString(hintRes)
            setText(value)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        val etHost = field(R.string.lan_host_hint, host)
        val etLabel = field(R.string.lan_label_hint, device?.label ?: "")
        val etUser = field(R.string.user_hint, device?.username.orEmpty().ifEmpty { prefs.user })
        val etPass = field(R.string.pass_hint, if (prefs.host == host) prefs.pass else "", true)
        val etDomain = field(R.string.domain_hint, device?.domain.orEmpty().ifEmpty { prefs.domain })
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        listOf(etHost, etLabel, etUser, etPass, etDomain).forEach {
            box.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.lan_connect, host.ifBlank { "NAS" }))
            .setView(box)
            .setPositiveButton(R.string.btn_connect) { _, _ ->
                val h = etHost.text.toString().trim()
                val u = etUser.text.toString().trim()
                val p = etPass.text.toString()
                val d = etDomain.text.toString().trim()
                if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
                    Toast.makeText(ctx, R.string.lan_need_cred, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                LanStore.add(
                    ctx, LanDevice(h, etLabel.text.toString().trim().ifEmpty { h }, u, d)
                )
                PrefsStore.save(ctx, PrefsStore.ConnectionPrefs(h, u, p, d, true))
                (activity as? MainActivity)?.openSmbTab(ConnectionParams(h, u, p, d))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHistory() {
        val list = LanStore.list(requireContext())
        if (list.isEmpty()) {
            Toast.makeText(requireContext(), R.string.lan_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.label}  ·  ${it.host}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tool_history)
            .setItems(labels) { _, i ->
                list.getOrNull(i)?.let { showConnectDialog(it.host, it) }
            }
            .show()
    }

    // ---- PageActions --------------------------------------------------------------------

    override fun toolbarActions(): List<Action> =
        listOf(Action.NEW, Action.REFRESH, Action.VIEW, Action.WINDOW, Action.HISTORY)

    override fun onToolbarAction(a: Action): Boolean {
        if (_binding == null) return false
        return when (a) {
            Action.NEW -> { showConnectDialog("", null); true }
            Action.REFRESH -> { scan(); true }
            Action.VIEW -> { refresh(); true }
            Action.HISTORY -> { showHistory(); true }
            else -> false
        }
    }

    override fun onBackPressed(): Boolean = false

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
