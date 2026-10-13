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
            onSelectChanged = { updateHeading() }
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
                showDarkMenu(it, listOf("Rename", "Delete", "Select all", "Done")) { i ->
                    when (i) { 0 -> renameSel(); 1 -> deleteSel(); 2 -> selectAll(); 3 -> exitEdit() }
                }
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
        val id = adapter.selected.singleOrNull()
        if (id == null || !isCustomId(id)) { toast("Select one album you created to rename"); return }
        val old = current.firstOrNull { it.id == id }?.name ?: ""
        askName(requireContext(), "Rename album", old) { n ->
            CustomAlbums.rename(id, n); exitEdit(); vm.publish()
        }
    }

    private fun deleteSel() {
        val ids = adapter.selected.filter { isCustomId(it) }
        if (ids.isEmpty()) { toast("Only albums you created can be deleted here"); return }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage("Delete ${ids.size} album(s)? Photos will NOT be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                ids.forEach { CustomAlbums.delete(it) }
                exitEdit(); vm.publish()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- List ----------
    private fun refresh() {
        if (!::adapter.isInitialized) return
        var l = vm.albums()
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