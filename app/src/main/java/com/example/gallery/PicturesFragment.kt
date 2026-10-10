package com.example.gallery

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PicturesFragment : Fragment(R.layout.fragment_pictures) {

    private val vm: GalleryViewModel by activityViewModels()
    private lateinit var grid: RecyclerView
    private lateinit var lm: GridLayoutManager
    private lateinit var adapter: PhotoAdapter
    private lateinit var search: SearchController
    private lateinit var title: TextView
    private lateinit var back: ImageView
    private lateinit var btnSelect: ImageView
    private lateinit var btnSearch: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var empty: TextView

    private var items: List<Media> = emptyList()
    private var query = ""
    private var pendingDelete: List<Uri> = emptyList()

    private val mode get() = arguments?.getString("mode") ?: "all"
    private val param get() = arguments?.getString("param") ?: ""
    private val canBack get() = arguments?.getBoolean("back") ?: false
    private val barTitle get() = arguments?.getString("title") ?: ""

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT < 30) Deleter.delete(requireContext(), pendingDelete)
            exitSelection(); vm.load()
        }
    }

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (adapter.selecting) exitSelection() else if (search.active) closeSearch()
        }
    }

    companion object {
        fun create(mode: String, param: String, title: String, back: Boolean) = PicturesFragment().apply {
            arguments = bundleOf("mode" to mode, "param" to param, "title" to title, "back" to back)
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        title = v.findViewById(R.id.title); title.useDisplay()
        back = v.findViewById(R.id.back)
        btnSelect = v.findViewById(R.id.btnSelect)
        btnSearch = v.findViewById(R.id.btnSearch)
        btnMore = v.findViewById(R.id.btnMore)
        empty = v.findViewById(R.id.empty); empty.useDisplay()
        grid = v.findViewById(R.id.grid)
        val input = v.findViewById<EditText>(R.id.searchInput)

        search = SearchController(input, listOf(title, btnSelect, btnMore), btnSearch) { query = it; rebuild() }

        adapter = PhotoAdapter({ r, p -> onTap(r, p) }, { r, p -> onLong(r, p) })
        lm = GridLayoutManager(requireContext(), Prefs.columns)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(p: Int) = if (adapter.getItemViewType(p) == PhotoAdapter.HEADER) lm.spanCount else 1
        }
        grid.layoutManager = lm
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(20)
        setupPinch()

        back.setOnClickListener { if (adapter.selecting) exitSelection() else requireActivity().finish() }
        btnSelect.setOnClickListener { startSelect() }
        btnSearch.setOnClickListener {
            when {
                adapter.selecting -> {
                    val u = selectedUris()
                    if (u.isEmpty()) Toast.makeText(requireContext(), "Select photos first", Toast.LENGTH_SHORT).show()
                    else Share.send(requireContext(), u)
                }
                search.active -> closeSearch()
                else -> { search.open(); backCb.isEnabled = true }
            }
        }
        btnMore.setOnClickListener {
            if (adapter.selecting) confirmDelete()
            else showDarkMenu(it, listOf("Select items", "Sort", "Grid size", "Refresh")) { i ->
                when (i) { 0 -> startSelect(); 1 -> sortDialog(); 2 -> gridDialog(); 3 -> vm.load() }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)
        updateBar()
        vm.all.observe(viewLifecycleOwner) { rebuild() }
        vm.loaded.observe(viewLifecycleOwner) { rebuild() }
    }

    // ---------- Pinch to change columns (2..7) ----------
    private fun setupPinch() {
        val sd = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var acc = 1f
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                if (acc > 1.28f) { setColumns(lm.spanCount - 1); acc = 1f }       // spread -> boro thumbnail
                else if (acc < 0.78f) { setColumns(lm.spanCount + 1); acc = 1f }  // pinch -> choto thumbnail
                return true
            }
        })
        sd.isQuickScaleEnabled = false
        grid.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                sd.onTouchEvent(e); return sd.isInProgress
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { sd.onTouchEvent(e) }
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }

    private fun setColumns(n: Int) {
        val c = n.coerceIn(2, 7)
        if (c == lm.spanCount) return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        lm.spanCount = c
        Prefs.columns = c
        lm.scrollToPositionWithOffset(first, 0)
        grid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        grid.alpha = 0.55f
        grid.animate().alpha(1f).setDuration(180).start()
    }

    // ---------- Data ----------
    private fun matchesMode(m: Media): Boolean = when (mode) {
        "album" -> m.bucketId.toString() == param
        "fav" -> Prefs.has("fav", "p${m.id}")
        "recent" -> m.date > System.currentTimeMillis() - 30L * 86_400_000L
        "month" -> monthKey(m.date) == param
        else -> true
    }

    private fun rebuild() {
        if (!::adapter.isInitialized) return
        var l = (vm.all.value ?: emptyList()).filter { matchesMode(it) }
        if (query.isNotBlank()) l = l.filter { it.name.contains(query.trim(), true) }
        l = when (Prefs.sort) {
            1 -> l.sortedBy { it.date }
            2 -> l.sortedBy { it.name.lowercase() }
            else -> l
        }
        items = l
        adapter.submitList(buildRows(l, Prefs.sort != 2))
        val show = l.isEmpty() && vm.loaded.value == true
        empty.visibility = if (show) View.VISIBLE else View.GONE
        empty.text = if (query.isNotBlank()) "No results" else "No pictures"
    }

    private fun buildRows(list: List<Media>, headers: Boolean): List<Row> {
        val out = ArrayList<Row>(list.size + 64)
        var last = Long.MIN_VALUE
        list.forEachIndexed { i, m ->
            if (headers) {
                val d = dayNum(m.date)
                if (d != last) { out.add(Row.Header(dayLabel(m.date))); last = d }
            }
            out.add(Row.Photo(m, i))
        }
        return out
    }

    // ---------- Selection ----------
    private fun onTap(r: Row.Photo, pos: Int) {
        if (adapter.selecting) toggle(r.m, pos)
        else {
            Session.items = items
            startActivity(ViewerActivity.intent(requireContext(), r.index))
        }
    }

    private fun onLong(r: Row.Photo, pos: Int) {
        if (!adapter.selecting) startSelect()
        grid.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        toggle(r.m, pos)
    }

    private fun toggle(m: Media, pos: Int) {
        if (!adapter.selected.add(m.id)) adapter.selected.remove(m.id)
        adapter.notifyItemChanged(pos)
        updateBar()
    }

    private fun startSelect() {
        if (search.active) closeSearch()
        adapter.selecting = true
        updateBar()
    }

    private fun exitSelection() {
        adapter.selecting = false
        adapter.selected.clear()
        adapter.notifyDataSetChanged()
        updateBar()
    }

    private fun closeSearch() { search.close(); backCb.isEnabled = adapter.selecting }

    private fun selectedUris() = items.filter { it.id in adapter.selected }.map { it.uri }

    private fun updateBar() {
        val sel = adapter.selecting
        back.visibility = if (sel || canBack) View.VISIBLE else View.GONE
        back.setImageResource(if (sel) R.drawable.ic_close else R.drawable.ic_arrow_back)
        if (sel) {
            title.text = "${adapter.selected.size} selected"
            btnSelect.visibility = View.GONE
            btnSearch.setImageResource(R.drawable.ic_share); btnSearch.contentDescription = "Share"
            btnMore.setImageResource(R.drawable.ic_delete); btnMore.contentDescription = "Delete"
        } else {
            title.text = barTitle
            btnSelect.visibility = View.VISIBLE
            btnSearch.setImageResource(R.drawable.ic_search); btnSearch.contentDescription = "Search"
            btnMore.setImageResource(R.drawable.ic_more); btnMore.contentDescription = "More options"
        }
        backCb.isEnabled = sel || search.active
    }

    private fun confirmDelete() {
        val uris = selectedUris()
        if (uris.isEmpty()) return
        pendingDelete = uris
        val go = {
            val sender = Deleter.delete(requireContext(), uris)
            if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            else { exitSelection(); vm.load() }
        }
        if (Build.VERSION.SDK_INT >= 30) go()   // system nijei confirm dialog dekhay
        else MaterialAlertDialogBuilder(requireContext()).setMessage("Delete ${uris.size} photo(s)?")
            .setPositiveButton("Delete") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
    }

    // ---------- Dialogs ----------
    private fun sortDialog() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Sort")
            .setSingleChoiceItems(arrayOf("Date, newest first", "Date, oldest first", "Name"), Prefs.sort) { d, w ->
                Prefs.sort = w; rebuild(); d.dismiss()
            }.show()
    }

    private fun gridDialog() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Grid size")
            .setSingleChoiceItems(Array(6) { "${it + 2} columns" }, lm.spanCount - 2) { d, w ->
                setColumns(w + 2); d.dismiss()
            }.show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && ::adapter.isInitialized) {
            if (adapter.selecting) exitSelection()
            if (search.active) closeSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        if (mode == "fav") rebuild() else adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }
}