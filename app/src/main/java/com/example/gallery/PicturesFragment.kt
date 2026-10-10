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
import androidx.core.app.ActivityOptionsCompat
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
    private var barTitleState = ""

    // drag-select state
    private var dragging = false
    private var dragAnchor = -1
    private var dragLast = -1
    private var dragLo = 0
    private var dragHi = 0
    private var dragX = 0f
    private var dragY = -1f
    private var dragBase: Set<Long> = emptySet()

    /** all | album | calbum | ustory | month | fav | recent | video | pick */
    private val mode get() = arguments?.getString("mode") ?: "all"
    private val param get() = arguments?.getString("param") ?: ""
    private val canBack get() = arguments?.getBoolean("back") ?: false
    private val barTitle get() = arguments?.getString("title") ?: ""
    private val store get() = if (mode == "ustory") CustomStories else CustomAlbums
    private val isColl get() = mode == "calbum" || mode == "ustory"

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT < 30) Deleter.delete(requireContext(), pendingDelete)
            exitSelection(); vm.load()
        }
    }

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                mode == "pick" -> requireActivity().finish()
                adapter.selecting -> exitSelection()
                search.active -> closeSearch()
            }
        }
    }

    companion object {
        fun create(mode: String, param: String, title: String, back: Boolean) = PicturesFragment().apply {
            arguments = bundleOf("mode" to mode, "param" to param, "title" to title, "back" to back)
        }
    }

    private fun act(label: String, f: () -> Unit): Pair<String, () -> Unit> = label to f

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onViewCreated(v: View, s: Bundle?) {
        barTitleState = if (isColl) (store.get(param.toLong())?.name ?: barTitle) else barTitle
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
        setupDragSelect()

        back.setOnClickListener {
            if (mode == "pick") requireActivity().finish()
            else if (adapter.selecting) exitSelection()
            else requireActivity().finish()
        }
        btnSelect.setOnClickListener { startSelect() }
        btnSearch.setOnClickListener {
            when {
                adapter.selecting && mode == "pick" -> confirmPick()
                adapter.selecting -> {
                    val u = selectedUris()
                    if (u.isEmpty()) toast("Select photos first") else Share.send(requireContext(), u)
                }
                search.active -> closeSearch()
                else -> { search.open(); backCb.isEnabled = true }
            }
        }
        btnMore.setOnClickListener { anchor ->
            val acts = ArrayList<Pair<String, () -> Unit>>()
            if (adapter.selecting) {
                acts.add(act("Select all") { selectAll() })
                if (mode != "pick") {
                    acts.add(act("Add to album") { addToCollection(CustomAlbums, "album") })
                    acts.add(act("Add to story") { addToCollection(CustomStories, "story") })
                    if (isColl) acts.add(act("Remove from here") { removeFromHere() })
                    if (mode == "ustory") acts.add(act("Set as cover") { setCover() })
                    acts.add(act("Delete") { confirmDelete() })
                }
            } else {
                acts.add(act("Select items") { startSelect() })
                acts.add(act("Sort") { sortDialog() })
                acts.add(act("Grid size") { gridDialog() })
                if (isColl || mode == "month") acts.add(act("Slideshow") { slideshow() })
                if (isColl) {
                    val noun = if (mode == "ustory") "story" else "album"
                    acts.add(act("Rename $noun") {
                        askName(requireContext(), "Rename $noun", barTitleState) { n ->
                            store.rename(param.toLong(), n); barTitleState = n; updateBar(); vm.publish()
                        }
                    })
                    acts.add(act("Delete $noun") { deleteCollection() })
                }
                acts.add(act("Refresh") { vm.load() })
            }
            showDarkMenu(anchor, acts.map { it.first }) { i -> acts[i].second() }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)
        if (mode == "pick") startSelect()
        updateBar()
        vm.all.observe(viewLifecycleOwner) { rebuild() }
        vm.loaded.observe(viewLifecycleOwner) { rebuild() }
    }

    override fun onDestroyView() {
        endDrag()
        super.onDestroyView()
    }

    /** Settings theke column change korle call hoy */
    fun applyColumns() { if (::lm.isInitialized) setColumns(Prefs.columns) }

    // ---------- Pinch to change columns (2..7) ----------
    private fun setupPinch() {
        val sd = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var acc = 1f
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                if (acc > 1.28f) { setColumns(lm.spanCount - 1); acc = 1f }
                else if (acc < 0.78f) { setColumns(lm.spanCount + 1); acc = 1f }
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

    // ---------- Drag to select ----------
    /** Long-press kore angul na tule drag korle, anchor theke angul porjonto sob item select hoy. Edge e dhorle auto-scroll. */
    private fun setupDragSelect() {
        grid.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (!dragging) return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> { dragX = e.x; dragY = e.y; updateDrag(); return true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> { dragX = e.x; dragY = e.y; updateDrag() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            if (dragY >= 0f) {
                val d = resources.displayMetrics.density
                val h = grid.height.toFloat()
                val zone = h * 0.18f
                val dy = when {
                    dragY < zone -> -(2 * d + (zone - dragY) / zone * 16 * d).toInt()
                    dragY > h - zone -> (2 * d + (dragY - (h - zone)) / zone * 16 * d).toInt()
                    else -> 0
                }
                if (dy != 0) { grid.scrollBy(0, dy); updateDrag() }
            }
            grid.postOnAnimation(this)
        }
    }

    private fun beginDrag(pos: Int) {
        dragging = true
        dragAnchor = pos
        dragLast = pos
        dragLo = pos; dragHi = pos
        dragY = -1f
        dragBase = HashSet(adapter.selected)
        applyRange(pos)
        grid.postOnAnimation(autoScroll)
    }

    private fun endDrag() {
        dragging = false
        dragAnchor = -1
        if (::grid.isInitialized) grid.removeCallbacks(autoScroll)
    }

    private fun updateDrag() {
        if (!dragging || dragY < 0f) return
        val y = dragY.coerceIn(0f, (grid.height - 1).toFloat())
        val child = grid.findChildViewUnder(dragX, y) ?: return
        val pos = grid.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION || pos == dragLast) return
        dragLast = pos
        applyRange(pos)
    }

    private fun applyRange(pos: Int) {
        val lo = minOf(dragAnchor, pos)
        val hi = maxOf(dragAnchor, pos)
        val ids = HashSet(dragBase)
        val list = adapter.currentList
        for (p in lo..hi) (list.getOrNull(p) as? Row.Photo)?.let { ids.add(it.m.id) }
        adapter.selected.clear()
        adapter.selected.addAll(ids)
        val from = minOf(lo, dragLo)
        val to = maxOf(hi, dragHi)
        adapter.notifyItemRangeChanged(from, to - from + 1, "sel")
        dragLo = lo; dragHi = hi
        updateBar()
    }

    // ---------- Data ----------
    private fun collIds(): Set<Long>? = when (mode) {
        "calbum" -> CustomAlbums.get(param.toLong())?.ids ?: emptySet()
        "ustory" -> CustomStories.get(param.toLong())?.ids ?: emptySet()
        else -> null
    }

    private fun matchesMode(m: Media, cids: Set<Long>?): Boolean = when (mode) {
        "album" -> m.bucketId.toString() == param
        "calbum", "ustory" -> cids != null && m.id in cids
        "fav" -> Prefs.has("fav", "p${m.id}")
        "recent" -> m.date > System.currentTimeMillis() - 30L * 86_400_000L
        "month" -> monthKey(m.date) == param
        "video" -> m.video
        else -> true
    }

    private fun rebuild() {
        if (!::adapter.isInitialized) return
        val cids = collIds()
        var l = (vm.all.value ?: emptyList()).filter { matchesMode(it, cids) }
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
        empty.text = if (query.isNotBlank()) "No results" else "Nothing here"
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
            val tv = grid.findViewHolderForAdapterPosition(pos)?.itemView
            val opts = if (tv != null) ActivityOptionsCompat.makeScaleUpAnimation(tv, 0, 0, tv.width, tv.height).toBundle() else null
            startActivity(ViewerActivity.intent(requireContext(), r.index), opts)
        }
    }

    private fun onLong(r: Row.Photo, pos: Int) {
        if (!adapter.selecting) startSelect()
        grid.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        beginDrag(pos)
    }

    private fun toggle(m: Media, pos: Int) {
        if (!adapter.selected.add(m.id)) adapter.selected.remove(m.id)
        adapter.notifyItemChanged(pos, "sel")
        updateBar()
    }

    private fun selectAll() {
        adapter.selected.addAll(items.map { it.id })
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        updateBar()
    }

    private fun startSelect() {
        if (search.active) closeSearch()
        adapter.selecting = true
        updateBar()
    }

    private fun exitSelection() {
        endDrag()
        adapter.selecting = false
        adapter.selected.clear()
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        updateBar()
    }

    private fun closeSearch() { search.close(); backCb.isEnabled = adapter.selecting }

    private fun selectedUris() = items.filter { it.id in adapter.selected }.map { it.uri }

    private fun updateBar() {
        val sel = adapter.selecting
        back.visibility = if (sel || canBack) View.VISIBLE else View.GONE
        back.setImageResource(if (sel) R.drawable.ic_close else R.drawable.ic_arrow_back)
        btnMore.setImageResource(R.drawable.ic_more); btnMore.contentDescription = "More options"
        if (sel) {
            title.text = "${adapter.selected.size} selected"
            btnSelect.visibility = View.GONE
            if (mode == "pick") {
                btnSearch.setImageResource(R.drawable.ic_select); btnSearch.contentDescription = "Done"
            } else {
                btnSearch.setImageResource(R.drawable.ic_share); btnSearch.contentDescription = "Share"
            }
        } else {
            title.text = barTitleState
            btnSelect.visibility = View.VISIBLE
            btnSearch.setImageResource(R.drawable.ic_search); btnSearch.contentDescription = "Search"
        }
        backCb.isEnabled = sel || search.active
    }

    // ---------- Albums / Stories actions ----------
    private fun confirmPick() {
        val ids = adapter.selected.toList()
        if (ids.isEmpty()) { toast("Select photos first"); return }
        (if (param == "story") CustomStories else CustomAlbums).create(barTitle, ids)
        vm.publish()
        requireActivity().finish()
    }

    private fun addToCollection(st: CollStore, noun: String) {
        val ids = adapter.selected.toList()
        if (ids.isEmpty()) { toast("Select photos first"); return }
        val list = st.all()
        val names = (listOf("+ New $noun") + list.map { it.name }).toTypedArray()
        MaterialAlertDialogBuilder(requireContext()).setTitle("Add to $noun")
            .setItems(names) { _, w ->
                if (w == 0) askName(requireContext(), "New $noun", "") { n ->
                    st.create(n, ids); afterAdd(ids.size)
                } else {
                    st.add(list[w - 1].id, ids); afterAdd(ids.size)
                }
            }.show()
    }

    private fun afterAdd(n: Int) {
        toast("Added $n item(s)")
        exitSelection()
        vm.publish()
    }

    private fun removeFromHere() {
        val ids = adapter.selected.toList()
        if (ids.isEmpty()) { toast("Select photos first"); return }
        store.remove(param.toLong(), ids)
        exitSelection()
        vm.publish()
    }

    private fun setCover() {
        if (adapter.selected.size != 1) { toast("Select exactly one item"); return }
        store.setCover(param.toLong(), adapter.selected.first())
        toast("Cover updated")
        exitSelection()
        vm.publish()
    }

    private fun deleteCollection() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage("Delete \"$barTitleState\"? Photos/videos will NOT be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(param.toLong()); vm.publish(); requireActivity().finish()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun slideshow() {
        if (items.isEmpty()) return
        Session.items = items
        startActivity(ViewerActivity.intent(requireContext(), 0, true))
    }

    private fun confirmDelete() {
        val uris = selectedUris()
        if (uris.isEmpty()) { toast("Select photos first"); return }
        pendingDelete = uris
        val go = {
            val sender = Deleter.delete(requireContext(), uris)
            if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            else { exitSelection(); vm.load() }
        }
        if (Build.VERSION.SDK_INT >= 30) go()
        else MaterialAlertDialogBuilder(requireContext()).setMessage("Delete ${uris.size} item(s)?")
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