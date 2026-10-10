package com.example.gallery

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object HideAlbumsDialog {
    fun show(ctx: Context, vm: GalleryViewModel) {
        val albums = vm.rawAlbums()
        val hidden = Prefs.getSet("hidden")
        val names = albums.map { "${it.name} (${it.count})" }.toTypedArray()
        val checked = albums.map { it.id.toString() in hidden }.toBooleanArray()
        MaterialAlertDialogBuilder(ctx).setTitle("Hide albums")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Done") { _, _ ->
                Prefs.setSet("hidden", albums.filterIndexed { i, _ -> checked[i] }.map { it.id.toString() }.toSet())
                vm.publish()
            }
            .setNegativeButton("Cancel", null).show()
    }
}

class AlbumsFragment : Fragment(R.layout.fragment_albums) {

    private val vm: GalleryViewModel by activityViewModels()
    private lateinit var adapter: AlbumAdapter
    private lateinit var search: SearchController
    private lateinit var empty: TextView
    private var query = ""

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { search.close(); isEnabled = false }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        v.findViewById<TextView>(R.id.heading).useDisplay()
        empty = v.findViewById(R.id.empty); empty.useDisplay()
        val btnSearch = v.findViewById<ImageView>(R.id.btnSearch)
        val btnMore = v.findViewById<ImageView>(R.id.btnMore)
        val spacer = v.findViewById<View>(R.id.spacer)
        val input = v.findViewById<EditText>(R.id.searchInput)

        search = SearchController(input, listOf(spacer, btnMore), btnSearch) { query = it; refresh() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)

        btnSearch.setOnClickListener {
            if (search.active) { search.close(); backCb.isEnabled = false }
            else { search.open(); backCb.isEnabled = true }
        }
        btnMore.setOnClickListener {
            showDarkMenu(it, listOf("Sort", "Hide albums")) { i ->
                if (i == 0) sortDialog() else HideAlbumsDialog.show(requireContext(), vm)
            }
        }

        adapter = AlbumAdapter { a ->
            startActivity(GridActivity.intent(requireContext(), "album", a.id.toString(), a.name))
        }
        v.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AlbumsFragment.adapter
            itemAnimator = null
        }
        vm.all.observe(viewLifecycleOwner) { refresh() }
        vm.loaded.observe(viewLifecycleOwner) { refresh() }
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        var l = vm.albums()
        if (query.isNotBlank()) l = l.filter { it.name.contains(query.trim(), true) }
        l = when (Prefs.albumSort) {
            1 -> l.sortedBy { it.name.lowercase() }
            2 -> l.sortedByDescending { it.count }
            else -> l.sortedByDescending { it.latest }
        }
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
        if (hidden && ::search.isInitialized) { search.close(); backCb.isEnabled = false }
    }
}