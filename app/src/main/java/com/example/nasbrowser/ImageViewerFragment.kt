package com.example.nasbrowser

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.nasbrowser.databinding.FragmentImageViewerBinding
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.viewmodel.BrowserViewModel
import java.io.File

/**
 * In-app full-screen-ish image viewer shown on top of the browser. It receives the list of image
 * entries in the current folder plus the start index, and offers three ways to move between
 * pictures in the same directory: 上一张 / 下一张 buttons, left/right swipes and DPAD left/right.
 *
 * Two sources are supported:
 *  - NAS  : paths are relative to a share and resolved through [BrowserViewModel.fetchToCache],
 *           which reuses the live SMB connection and caches by size + mtime.
 *  - 本机 : paths are absolute device paths and are decoded directly, with no download.
 */
class ImageViewerFragment : Fragment() {
    private var _binding: FragmentImageViewerBinding? = null
    private val binding get() = _binding!!
    private val vm: BrowserViewModel by activityViewModels()

    private var share: String = ""
    private var images: List<SmbEntry> = emptyList()
    private var start: Int = 0
    private var local: Boolean = false
    private var chromeVisible = true

    companion object {
        private const val KEY_SHARE = "share"
        private const val KEY_IMAGES = "images"
        private const val KEY_START = "start"
        private const val KEY_LOCAL = "local"

        /** Images inside a NAS share. */
        fun newInstance(share: String, images: ArrayList<SmbEntry>, start: Int) =
            build(share, images, start, false)

        /** Images on the device (paths are absolute). */
        fun newInstanceLocal(images: ArrayList<SmbEntry>, start: Int) =
            build("", images, start, true)

        private fun build(
            share: String, images: ArrayList<SmbEntry>, start: Int, local: Boolean
        ): ImageViewerFragment = ImageViewerFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_SHARE, share)
                putParcelableArrayList(KEY_IMAGES, images)
                putInt(KEY_START, start)
                putBoolean(KEY_LOCAL, local)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        share = args.getString(KEY_SHARE) ?: ""
        @Suppress("DEPRECATION")
        images = args.getParcelableArrayList<SmbEntry>(KEY_IMAGES)?.toList() ?: emptyList()
        start = args.getInt(KEY_START, 0)
        local = args.getBoolean(KEY_LOCAL, false)

        if (images.isEmpty()) {
            parentFragmentManager.popBackStack()
            return
        }
        start = start.coerceIn(0, images.lastIndex)

        val adapter = ImagePagerAdapter(
            images,
            { item ->
                if (local) File(item.path).takeIf { it.isFile } else vm.fetchToCache(share, item)
            },
            viewLifecycleOwner.lifecycleScope,
            ::toggleChrome
        )
        binding.pager.adapter = adapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.setCurrentItem(start, false)
        updateChrome(start)
        // While the gallery is open the only chrome should be its own top/bottom bars; hide the
        // browser's tab strip and file toolbar so taps can't reach the list underneath.
        setBrowserChrome(visible = false)

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateChrome(position)
        })

        binding.btnPrev.setOnClickListener { move(-1) }
        binding.btnNext.setOnClickListener { move(1) }

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Foldables / tablets with a keyboard: DPAD left/right behaves like the on-screen arrows.
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                else -> false
            }
        }

        // Consume the system back gesture while the gallery is open so it closes the viewer
        // instead of navigating the browser underneath.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentFragmentManager.popBackStack()
                }
            }
        )
    }

    /** Move to the neighbouring picture with the same animated transition as a swipe. */
    private fun move(delta: Int) {
        if (_binding == null || images.isEmpty()) return
        val next = (binding.pager.currentItem + delta).coerceIn(0, images.lastIndex)
        if (next != binding.pager.currentItem) binding.pager.setCurrentItem(next, true)
    }

    /** A single tap on the picture hides the top/bottom bars; tapping again brings them back. */
    private fun toggleChrome() {
        if (_binding == null) return
        chromeVisible = !chromeVisible
        listOf(binding.toolbar, binding.bottomBar).forEach { v ->
            v.animate().cancel()
            if (chromeVisible) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(160L).start()
            } else {
                v.animate().alpha(0f).setDuration(160L)
                    .withEndAction { if (_binding != null) v.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun updateChrome(pos: Int) {
        if (_binding == null) return
        val name = images.getOrNull(pos)?.name ?: ""
        binding.toolbar.title = name
        binding.toolbar.subtitle = getString(R.string.gallery_counter, pos + 1, images.size)
        binding.tvCounter.text = getString(R.string.gallery_counter, pos + 1, images.size)
        binding.tvName.text = name
        binding.btnPrev.isEnabled = pos > 0
        binding.btnNext.isEnabled = pos < images.lastIndex
        binding.btnPrev.alpha = if (pos > 0) 1f else 0.35f
        binding.btnNext.alpha = if (pos < images.lastIndex) 1f else 0.35f
    }

    private fun setBrowserChrome(visible: Boolean) {
        val act = activity ?: return
        val state = if (visible) View.VISIBLE else View.GONE
        act.findViewById<View>(R.id.topBar)?.visibility = state
        act.findViewById<View>(R.id.toolBar)?.visibility = state
    }

    override fun onDestroyView() {
        setBrowserChrome(visible = true)
        binding.pager.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
