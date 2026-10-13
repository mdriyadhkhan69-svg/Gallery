package com.example.gallery

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

class PicturesFragment : Fragment(R.layout.fragment_pictures) {

    private val vm: GalleryViewModel by activityViewModels()
    private lateinit var grid: RecyclerView
    private lateinit var lm: ZoomGridLayoutManager
    private lateinit var adapter: PhotoAdapter
    private lateinit var search: SearchController
    private lateinit var title: TextView
    private lateinit var back: ImageView
    private lateinit var btnDup: ImageView
    private var dupMode = false
    private lateinit var btnSearch: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var empty: TextView

    private var items: List<Media> = emptyList()
    private var query = ""
    private var barTitleState = ""
    private lateinit var sub: TextView
    private var subText = ""
    private var previewer: GridPreviewer? = null
    private lateinit var pullTitle: TextView
    private lateinit var pullSub: TextView
    private var pullSubText = ""
    private var pullHdr: PullHeader? = null

    // zoom state
    private var zoomAnim: ValueAnimator? = null
    private var pinchX = 0f
    private var pinchY = 0f

    // drag-select state
    private var dragging = false
    private var dragAnchor = -1
    private var dragLast = -1
    private var dragLo = 0
    private var dragHi = 0
    private var dragX = 0f
    private var dragY = -1f
    private var dragBase: Set<Long> = emptySet()
    private var dragAdd = true

    /** all | album | calbum | ustory | month | fav | recent | video | pick */
    private val mode get() = arguments?.getString("mode") ?: "all"
    private val param get() = arguments?.getString("param") ?: ""
    private val canBack get() = arguments?.getBoolean("back") ?: false
    private val barTitle get() = arguments?.getString("title") ?: ""
    private val store get() = if (mode == "ustory") CustomStories else CustomAlbums
    private val isColl get() = mode == "calbum" || mode == "ustory"

    private val deleter = DeleteHelper(this, { requireContext() }) { removed, _ ->
        if (removed.isNotEmpty()) exitSelection()
        vm.load()
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

    /** Selection / search / drag cholle section swipe off thakbe */
    fun swipeBlocked(): Boolean =
        !::adapter.isInitialized || !::search.isInitialized || adapter.selecting || search.active || dragging

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onViewCreated(v: View, s: Bundle?) {
        barTitleState = if (isColl) (store.get(param.toLong())?.name ?: barTitle) else barTitle
        title = v.findViewById(R.id.title); title.useDisplay()
        sub = v.findViewById(R.id.subtitle); sub.useDisplay()
        back = v.findViewById(R.id.back)
        btnDup = v.findViewById(R.id.btnDup)
        btnSearch = v.findViewById(R.id.btnSearch)
        btnMore = v.findViewById(R.id.btnMore)
        empty = v.findViewById(R.id.empty); empty.useDisplay()
        grid = v.findViewById(R.id.grid)
        val input = v.findViewById<EditText>(R.id.searchInput)

        search = SearchController(input, listOf<View>(v.findViewById(R.id.titleBox), btnDup, btnMore), btnSearch) { query = it; rebuild() }

        adapter = PhotoAdapter({ r, p -> onTap(r, p) }, { r, p -> onLong(r, p) }, { p -> onHeaderTap(p) }, { m -> onDup(m) })
        lm = ZoomGridLayoutManager(requireContext()) { adapter.currentList }
        val startLevel = (Prefs.getStr("zoomLvl", "0").toIntOrNull() ?: 0).coerceIn(0, ZoomGridLayoutManager.MAX_ZOOM)
        lm.setLevel(startLevel)
        saveLevel(startLevel)
        grid.layoutManager = lm
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(20)
        setupPinch()
        setupDragSelect()
        ScrollAssist.attach(grid, v.findViewById(R.id.fastScroll), v.findViewById(R.id.btnTop))
        if (mode != "all" && mode != "pick") previewer = GridPreviewer(grid, adapter)
        pullTitle = v.findViewById(R.id.pullTitle); pullTitle.useDisplay()
        pullSub = v.findViewById(R.id.pullSub); pullSub.useDisplay()
        val titleBoxView = v.findViewById<View>(R.id.titleBox)
        pullHdr = PullHeader(
            grid, v.findViewById(R.id.pullHeader),
            onProgress = { f -> titleBoxView.alpha = (1f - f * 1.6f).coerceIn(0f, 1f) },
            enabled = { !adapter.selecting && !dragging && mode != "pick" }
        )

        back.setOnClickListener {
            if (mode == "pick") requireActivity().finish()
            else if (adapter.selecting) exitSelection()
            else requireActivity().finish()
        }
        btnDup.setOnClickListener { setDupMode(!dupMode) }
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
        vm.dupTick.observe(viewLifecycleOwner) { rebuild() }
    }

    override fun onDestroyView() {
        zoomAnim?.cancel()
        zoomAnim = null
        previewer?.release()
        previewer = null
        pullHdr = null
        endDrag()
        super.onDestroyView()
    }

    /** Settings theke column change korle call hoy */
    fun applyColumns() {
        if (!::lm.isInitialized) return
        val cur = lm.zoom.roundToInt()
        if (Prefs.columns != max(3, cur + 2)) {
            animateZoomTo((Prefs.columns - 2).coerceIn(0, ZoomGridLayoutManager.MAX_ZOOM), grid.width / 2f, grid.height / 2f)
        }
    }

    /** Active tab abar tap korle top e smooth scroll */
    fun scrollToTop() {
        if (!::grid.isInitialized || !isAdded) return
        if (zoomAnim?.isRunning == true) return
        grid.stopScroll()
        if (!grid.canScrollVertically(-1)) return
        if (lm.findFirstVisibleItemPosition() > 40) {
            lm.scrollToPositionWithOffset(40, 0)
            grid.postDelayed({ grid.smoothScrollToPosition(0) }, 40)
        } else grid.smoothScrollToPosition(0)
    }

    // ---------- Pinch zoom: 0 = normal custom layout, 1..5 = uniform 3..7 columns ----------
    private fun saveLevel(l: Int) {
        Prefs.putStr("zoomLvl", l.toString())
        Prefs.columns = max(3, l + 2)
    }

    private fun animateZoomTo(target: Int, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        val t = target.coerceIn(0, ZoomGridLayoutManager.MAX_ZOOM)
        val from = lm.zoom
        saveLevel(t)
        if (abs(from - t) < 0.001f) { lm.setZoom(t.toFloat(), fx, fy); return }
        zoomAnim = ValueAnimator.ofFloat(from, t.toFloat()).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { lm.setZoom(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    private fun setupPinch() {
        var dir = 0f
        val sd = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (dragging) return false
                zoomAnim?.cancel()
                grid.stopScroll()
                dir = 0f
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val ds = -ln(d.scaleFactor) * 2.4f
                if (ds != 0f) dir = ds
                pinchX = d.focusX
                pinchY = d.focusY
                lm.setZoom(lm.zoom + ds, pinchX, pinchY)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                val z = lm.zoom
                val lo = floor(z)
                val f = z - lo
                val t = when {
                    dir > 0f -> if (f > 0.3f) lo + 1f else lo
                    dir < 0f -> if (f < 0.7f) lo else lo + 1f
                    else -> z.roundToInt().toFloat()
                }
                animateZoomTo(t.toInt(), pinchX, pinchY)
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
        val aid = (adapter.currentList.getOrNull(pos) as? Row.Photo)?.m?.id
        dragAdd = aid == null || aid !in dragBase
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
        for (p in lo..hi) (list.getOrNull(p) as? Row.Photo)?.let { if (dragAdd) ids.add(it.m.id) else ids.remove(it.m.id) }
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
        "album" -> m.bucketId.toString() == param && !Prefs.has("albumGone", m.id.toString())
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
        val base = (vm.all.value ?: emptyList()).filter { matchesMode(it, cids) }
        subText = summary(base)
        pullSubText = countLabel(base.size, base.count { it.video }).replace(", ", " · ")
        var l = base
        if (query.isNotBlank()) l = l.filter { it.name.contains(query.trim(), true) }
        val rows: List<Row>
        if (dupMode) {
            val built = buildDupRows(l)
            items = built.first
            rows = built.second
        } else {
            l = when (Prefs.sort) {
                1 -> l.sortedBy { it.date }
                2 -> l.sortedBy { it.name.lowercase() }
                else -> l
            }
            items = l
            rows = buildRows(l, Prefs.sort != 2)
        }
        adapter.submitList(rows) { adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel") }
        val show = rows.isEmpty() && vm.loaded.value == true
        empty.visibility = if (show) View.VISIBLE else View.GONE
        empty.text = when {
            query.isNotBlank() -> "No results"
            dupMode -> if (Dups.ready) "No duplicate files found" else "Scanning for duplicates…"
            else -> "Nothing here"
        }
        updateBar()
    }

    private fun buildDupRows(list: List<Media>): Pair<List<Media>, List<Row>> {
        val byId = list.associateBy { it.id }
        val groups = Dups.groups.values
            .map { ids -> ids to ids.mapNotNull { byId[it] } }
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.second.first().date }
        val flat = ArrayList<Media>()
        val rows = ArrayList<Row>()
        groups.forEachIndexed { gi, g ->
            rows.add(Row.Header("Group ${gi + 1} · ${g.first.size} files"))
            g.second.forEach { rows.add(Row.Photo(it, flat.size)); flat.add(it) }
        }
        return flat to rows
    }

    private fun setDupMode(on: Boolean) {
        dupMode = on
        adapter.dupActive = on
        updateBar()
        rebuild()
        grid.post { grid.scrollToPosition(0) }
    }

    private fun onDup(m: Media) {
        val key = Dups.groupOf[m.id] ?: return
        previewer?.stop()
        startActivity(DupGroupActivity.intent(requireContext(), key, m.id))
    }

    private fun onHeaderTap(pos: Int) {
        val ids = adapter.sectionIds(pos)
        if (ids.isEmpty()) return
        if (ids.all { it in adapter.selected }) adapter.selected.removeAll(ids.toSet()) else adapter.selected.addAll(ids)
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        updateBar()
    }

    private fun summary(l: List<Media>): String {
        if (l.isEmpty()) return ""
        val v = l.count { it.video }
        val p = l.size - v
        val parts = ArrayList<String>()
        if (p > 0) parts.add("$p ${if (p == 1) "photo" else "photos"}")
        if (v > 0) parts.add("$v ${if (v == 1) "video" else "videos"}")
        return "${l.size} ${if (l.size == 1) "item" else "items"} · ${parts.joinToString(", ")}"
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
            previewer?.stop()
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
        pullHdr?.collapse()
        if (search.active) closeSearch()
        adapter.selecting = true
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        updateBar()
    }

    private fun exitSelection() {
        endDrag()
        adapter.selecting = false
        adapter.selected.clear()
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        updateBar()
    }

    private fun closeSearch() { search.close(); backCb.isEnabled = adapter.selecting; updateBar() }

    private fun selectedUris() = items.filter { it.id in adapter.selected }.map { it.uri }

    private fun updateBar() {
        val sel = adapter.selecting
        back.visibility = if (sel || canBack) View.VISIBLE else View.GONE
        back.setImageResource(if (sel) R.drawable.ic_close else R.drawable.ic_arrow_back)
        btnMore.setImageResource(R.drawable.ic_more); btnMore.contentDescription = "More options"
        if (sel) {
            title.text = "${adapter.selected.size} selected"
            if (mode == "pick") {
                btnSearch.setImageResource(R.drawable.ic_select); btnSearch.contentDescription = "Done"
            } else {
                btnSearch.setImageResource(R.drawable.ic_share); btnSearch.contentDescription = "Share"
            }
        } else {
            title.text = barTitleState
            btnSearch.setImageResource(R.drawable.ic_search); btnSearch.contentDescription = "Search"
        }
        btnDup.visibility = if (sel || mode == "pick" || mode == "album" || mode == "calbum" || search.active) View.GONE else View.VISIBLE
        btnDup.setColorFilter(if (dupMode) Colors.BLUE else Color.WHITE)
        backCb.isEnabled = sel || search.active
        previewer?.setBlocked(sel)
        sub.text = subText
        sub.visibility = if (!sel && mode != "all" && subText.isNotEmpty()) View.VISIBLE else View.GONE
        pullTitle.text = if (mode == "all") "Pictures" else barTitleState
        pullSub.text = pullSubText
        pullSub.visibility = if (mode != "all" && pullSubText.isNotEmpty()) View.VISIBLE else View.GONE
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
        deleter.confirmAndDelete(uris)
    }

    // ---------- Dialogs ----------
    private fun sortDialog() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Sort")
            .setSingleChoiceItems(arrayOf("Date, newest first", "Date, oldest first", "Name"), Prefs.sort) { d, w ->
                Prefs.sort = w; rebuild(); d.dismiss()
            }.show()
    }

    private fun gridDialog() {
        val labels = arrayOf("Normal", "3 columns", "4 columns", "5 columns", "6 columns", "7 columns")
        MaterialAlertDialogBuilder(requireContext()).setTitle("Grid size")
            .setSingleChoiceItems(labels, lm.zoom.roundToInt()) { d, w ->
                animateZoomTo(w, grid.width / 2f, grid.height / 2f); d.dismiss()
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
        if (mode == "fav") rebuild() else adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        previewer?.resume()
    }

    override fun onPause() {
        previewer?.pause()
        super.onPause()
    }
}