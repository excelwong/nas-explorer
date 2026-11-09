package com.example.nasbrowser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.nasbrowser.databinding.FragmentDetailBinding
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.util.formatSize
import com.example.nasbrowser.viewmodel.BrowserViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Right-hand pane on wide screens: read-only detail of the currently highlighted entry. */
class DetailPaneFragment : Fragment() {
    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private val selectionVm: SelectionViewModel by activityViewModels()
    private val vm: BrowserViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectionVm.selected.observe(viewLifecycleOwner) { render(it) }
        vm.dialect.observe(viewLifecycleOwner) { d ->
            binding.tvProtocol.text = if (d.isEmpty()) {
                getString(R.string.detail_protocol_idle)
            } else {
                getString(R.string.detail_protocol, d)
            }
        }
    }

    private fun render(e: SmbEntry?) {
        binding.detailContent.visibility = if (e == null) View.GONE else View.VISIBLE
        binding.tvEmpty.visibility = if (e == null) View.VISIBLE else View.GONE
        if (e == null) return
        binding.imgIcon.setImageResource(if (e.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
        binding.tvName.text = e.name
        binding.tvType.text = getString(
            if (e.isDirectory) R.string.folder_label else R.string.file_label
        )
        binding.tvSize.text = if (e.isDirectory) getString(R.string.unknown_size) else formatSize(e.size)
        binding.tvTime.text = if (e.lastModified > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(e.lastModified))
        } else {
            getString(R.string.unknown_size)
        }
        binding.tvPath.text = e.path
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
