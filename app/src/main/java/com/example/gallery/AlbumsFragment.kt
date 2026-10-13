package com.example.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object HideAlbumsDialog {
    fun show(ctx: Context, vm: GalleryViewModel) {
        ctx.startActivity(Intent(ctx, HideAlbumsActivity::class.java))
    }
}

class AlbumsFragment : Fragment(R.layout.fragment_albums) {

    private val vm: GalleryViewModel by activityViewModels()
    private lateinit var adapter: AlbumAdapter
    private lateinit var search: SearchController
    private lateinit var empty: TextView
    private lateinit var heading: TextView
    private var query = ""
    private var current: List<Album> = emptyList()
    private var pendingMedia: List<Media> = emptyList()
    private var pendingRec: Long? = null
    private val deleter = DeleteHelper(this, { requireContext() }) { removed, failed -> afterAlbumTrash(removed, failed) }
        .apply { quiet = true }

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (adapter.editing) exitEdit() else if (search.active) search.close()
            syncBack()
        }
    }

    private fun syncBack() { backCb.isEnabled = adapter.editing || search.active }

    fun swipeBlocked(): Boolean =
        !::adapter.isInitialized || !::search.isInitialized || adapter.editing || search.active

    /** Active tab abar tap korle top e smooth scroll */
    fun scrollToTop() {
        val rv = view?.findViewById<RecyclerView>(R.id.list) ?: return
        val m = rv.layoutManager as? LinearLayoutManager ?: return
        rv.stopScroll()
        if (!rv.canScrollVertically(-1)) return
        if (m.findFirstVisibleItemPosition() > 40) {
            m.scrollToPositionWithOffset(40, 0)
            rv.postDelayed({ rv.smoothScrollToPosition(0) }, 40)
        } else rv.smoothScrollToPosition(0)
    }
    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onViewCreated(v: View, s: Bundle?) {
        heading = v.findViewById(R.id.heading); heading.useDisplay()
        empty = v.findViewById(R.id.empty); empty.useDisplay()
        val btnAdd = v.findViewById<ImageView>(R.id.btnAdd)
        val btnSearch = v.findViewById<ImageView>(R.id.btnSearch)
        val btnMore = v.findViewById<ImageView>(R.id.btnMore)
        val spacer = v.findViewById<View>(R.id.spacer)
        val input = v.findViewById<EditText>(R.id.searchInput)

        search = SearchController(input, listOf(spacer, btnAdd, btnMore), btnSearch) { query = it; refresh() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)

        adapter = AlbumAdapter(
            onClick = { a ->
                val mode = if (isCustomId(a.id)) "calbum" else "album"
                startActivity(GridActivity.intent(requireContext(), mode, a.id.toString(), a.name))
            },
            onSelectChanged = { updateHeading() },
            onLong = { a ->
                if (!adapter.editing) enterEdit()
                adapter.selected.add(a.id)
                adapter.notifyDataSetChanged()
                updateHeading()
            }
        )

        btnAdd.setOnClickListener {
            askName(requireContext(), "New album", "") { n ->
                // naam dewar por photo select korar screen
                startActivity(GridActivity.intent(requireContext(), "pick", "album", n))
            }
        }
        btnSearch.setOnClickListener {
            if (search.active) search.close() else search.open()
            syncBack()
        }
        btnMore.setOnClickListener {
            if (adapter.editing) {
                val acts = ArrayList<Pair<String, () -> Unit>>()
                if (adapter.selected.isNotEmpty()) acts.add("Rename" to { renameSel() })
                acts.add("Select all" to { selectAll() })
                if (adapter.selected.isNotEmpty()) acts.add("Delete" to { deleteSel() })
                showDarkMenu(it, acts.map { a -> a.first }) { i -> acts[i].second() }
            } else {
                showDarkMenu(it, listOf("Edit", "Select all", "Sort", "Hide albums", "Lock albums")) { i ->
                    when (i) {
                        0 -> enterEdit()
                        1 -> { enterEdit(); selectAll() }
                        2 -> sortDialog()
                        3 -> HideAlbumsDialog.show(requireContext(), vm)
                        4 -> LockFlow.open(requireActivity() as androidx.appcompat.app.AppCompatActivity)
                    }
                }
            }
        }

        val albumList = v.findViewById<RecyclerView>(R.id.list)
        albumList.layoutManager = LinearLayoutManager(requireContext())
        albumList.adapter = adapter
        albumList.itemAnimator = null
        ScrollAssist.attach(albumList, v.findViewById(R.id.fastScroll), v.findViewById(R.id.btnTop))
        v.findViewById<TextView>(R.id.pullTitle).useDisplay()
        PullHeader(albumList, v.findViewById(R.id.pullHeader), target = v.findViewById(R.id.content))
        vm.all.observe(viewLifecycleOwner) { refresh() }
        vm.loaded.observe(viewLifecycleOwner) { refresh() }
    }

    // ---------- Edit mode ----------
    private fun enterEdit() {
        adapter.editing = true
        adapter.selected.clear()
        adapter.notifyDataSetChanged()
        updateHeading(); syncBack()
    }

    private fun exitEdit() {
        adapter.editing = false
        adapter.selected.clear()
        adapter.notifyDataSetChanged()
        updateHeading(); syncBack()
    }

    private fun selectAll() {
        adapter.selected.addAll(current.map { it.id })
        adapter.notifyDataSetChanged()
        updateHeading()
    }

    private fun updateHeading() {
        heading.text = if (adapter.editing) "${adapter.selected.size} selected" else "My albums"
    }

    private fun renameSel() {
        val sel = adapter.selected.toList()
        if (sel.isEmpty()) { toast("Select an album first"); return }
        if (sel.size > 1) { toast("Only one album can be renamed at a time"); return }
        val id = sel[0]
        val old = current.firstOrNull { it.id == id }?.name ?: ""
        askName(requireContext(), "Rename album", old) { n ->
            if (isCustomId(id)) CustomAlbums.rename(id, n) else AlbumNames.put(id, n)
            exitEdit()
            vm.publish()
        }
    }

    private fun deleteSel() {
        val ids = adapter.selected.toList()
        if (ids.isEmpty()) return
        val toRecover = Prefs.albumToRecover
        val where = if (toRecover) "Recover Images" else "the Recycle Bin"
        val msg = (if (ids.size == 1) "Delete this album?" else "Delete ${ids.size} albums?") +
                " Their photos and videos will be moved to $where."
        showDeleteSheet(requireContext(), msg, "Delete") { runAlbumDelete(ids, toRecover) }
    }

    private fun mediaWord(l: Collection<Media>): String {
        val v = l.count { it.video }
        return when {
            v == 0 -> "images"
            v == l.size -> "videos"
            else -> "images and videos"
        }
    }

    private fun runAlbumDelete(ids: List<Long>, toRecover: Boolean) {
        val all = vm.all.value.orEmpty()
        val media = LinkedHashMap<Long, Media>()
        val recId = CustomAlbums.all().firstOrNull { it.name == RECOVER_NAME }?.id
        for (id in ids) {
            if (!isCustomId(id)) all.filter { it.bucketId == id }.forEach { media[it.id] = it }
            else if (id == recId) {
                if (!toRecover) {
                    val s = CustomAlbums.get(id)?.ids ?: emptySet()
                    all.filter { it.id in s }.forEach { media[it.id] = it }
                }
            } else CustomAlbums.delete(id)
        }
        if (toRecover) {
            if (media.isNotEmpty()) {
                val rid = recId ?: CustomAlbums.create(RECOVER_NAME, emptyList())
                CustomAlbums.add(rid, media.keys)
                Prefs.setSet("albumGone", Prefs.getSet("albumGone") + media.keys.map { it.toString() })
                toast("Your ${mediaWord(media.values)} have been moved to Recover Images")
            }
            exitEdit()
            vm.publish()
            return
        }
        if (media.isEmpty()) { exitEdit(); vm.publish(); return }
        if (!Deleter.binAvailable()) {
            toast("Recycle Bin needs Android 11+. Turn on Recover Images in Settings.")
            return
        }
        pendingMedia = media.values.toList()
        pendingRec = if (recId != null && recId in ids) recId else null
        deleter.begin(pendingMedia.map { it.uri }, true)
    }

    private fun afterAlbumTrash(removed: List<android.net.Uri>, failed: Int) {
        val done = pendingMedia.filter { it.uri in removed }
        val rec = pendingRec
        pendingMedia = emptyList()
        pendingRec = null
        exitEdit()
        when {
            done.isEmpty() -> toast("Couldn't move to Recycle Bin")
            failed == 0 -> {
                rec?.let { CustomAlbums.delete(it) }
                toast("Your ${mediaWord(done)} have been moved to Recycle Bin")
            }
            else -> toast("${done.size} moved, $failed couldn't be moved")
        }
        vm.load()
    }

    // ---------- List ----------
    private fun refresh() {
        if (!::adapter.isInitialized) return
        var l = vm.albums()
        l = l.filter { !(it.name == RECOVER_NAME && isCustomId(it.id) && it.count == 0) }
        if (query.isNotBlank()) l = l.filter { it.name.contains(query.trim(), true) }
        l = when (Prefs.albumSort) {
            1 -> l.sortedBy { it.name.lowercase() }
            2 -> l.sortedByDescending { it.count }
            else -> l.sortedByDescending { it.latest }
        }
        current = l
        adapter.submitList(l)
        empty.visibility = if (l.isEmpty() && vm.loaded.value == true) View.VISIBLE else View.GONE
        empty.text = if (query.isNotBlank()) "No results" else "No albums"
    }

    private fun sortDialog() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Sort")
            .setSingleChoiceItems(arrayOf("Recent", "Name", "Number of pictures"), Prefs.albumSort) { d, w ->
                Prefs.albumSort = w; refresh(); d.dismiss()
            }.show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && ::search.isInitialized) {
            search.close()
            if (adapter.editing) exitEdit()
            syncBack()
        }
    }
}