package com.example.nasbrowser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.nasbrowser.databinding.FragmentSimpleListBinding
import com.example.nasbrowser.util.Bookmark
import com.example.nasbrowser.util.BookmarkStore

/** Saved folders/files: tap to jump, long-press for open / rename / remove. */
class BookmarksFragment : Fragment() {
    private var _binding: FragmentSimpleListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ArrayAdapter<String>
    private var items = listOf<Bookmark>()

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
        binding.tvTitle.text = getString(R.string.nav_bookmarks)
        adapter = ArrayAdapter(requireContext(), R.layout.item_list_line, mutableListOf())
        binding.listView.adapter = adapter
        binding.listView.setOnItemClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { (activity as? MainActivity)?.openBookmark(it) }
        }
        binding.listView.setOnItemLongClickListener { _, _, pos, _ ->
            val b = items.getOrNull(pos)
            if (b != null) showActions(b)
            true
        }
        refresh()
    }

    private fun showActions(b: Bookmark) {
        val opts = arrayOf(
            getString(R.string.bookmark_open),
            getString(R.string.bookmark_rename),
            getString(R.string.action_delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(b.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> (activity as? MainActivity)?.openBookmark(b)
                    1 -> showRenameDialog(b)
                    2 -> {
                        BookmarkStore.remove(requireContext(), b)
                        refresh()
                        Toast.makeText(requireContext(), R.string.bookmark_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(b: Bookmark) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.bookmark_name_hint)
            setText(b.name)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bookmark_rename)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    BookmarkStore.rename(requireContext(), b, name)
                    refresh()
                    Toast.makeText(requireContext(), R.string.bookmark_renamed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    private fun refresh() {
        items = BookmarkStore.list(requireContext())
        adapter.clear()
        adapter.addAll(items.map {
            val loc = if (it.isLocal) {
                "${getString(R.string.bookmark_local_label)}  ${it.path}"
            } else {
                "\\\\${it.host}\\${it.share}" +
                    if (it.path.isEmpty()) "" else "\\${it.path.replace('/', '\\')}"
            }
            "${it.name}    $loc"
        })
        adapter.notifyDataSetChanged()
        val empty = items.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.listView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmpty.text = getString(R.string.bookmarks_empty)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
