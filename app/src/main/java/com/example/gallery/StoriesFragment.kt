package com.example.gallery

import android.graphics.Color
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

class StoriesFragment : Fragment(R.layout.fragment_stories) {

    private val vm: GalleryViewModel by activityViewModels()
    private lateinit var adapter: StoryAdapter
    private lateinit var search: SearchController
    private lateinit var empty: TextView
    private lateinit var btnHeart: ImageView
    private var favOnly = false
    private var query = ""

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { search.close(); isEnabled = false }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        v.findViewById<TextView>(R.id.heading).useDisplay()
        empty = v.findViewById(R.id.empty); empty.useDisplay()
        btnHeart = v.findViewById(R.id.btnHeart)
        val btnSearch = v.findViewById<ImageView>(R.id.btnSearch)
        val btnMore = v.findViewById<ImageView>(R.id.btnMore)
        val spacer = v.findViewById<View>(R.id.spacer)
        val input = v.findViewById<EditText>(R.id.searchInput)

        search = SearchController(input, listOf(spacer, btnHeart, btnMore), btnSearch) { query = it; refresh() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)

        btnSearch.setOnClickListener {
            if (search.active) { search.close(); backCb.isEnabled = false }
            else { search.open(); backCb.isEnabled = true }
        }
        btnHeart.setOnClickListener {
            favOnly = !favOnly
            btnHeart.setColorFilter(if (favOnly) Colors.HEART else Color.WHITE)
            refresh()
        }
        btnMore.setOnClickListener {
            showDarkMenu(it, listOf("Mark all as viewed", "Refresh")) { i ->
                if (i == 0) {
                    Prefs.setSet("viewed", Prefs.getSet("viewed") + vm.stories().map { st -> st.key })
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                } else vm.load()
            }
        }

        adapter = StoryAdapter(
            onClick = { st -> startActivity(GridActivity.intent(requireContext(), "month", st.key, st.title)) },
            onHeartChanged = { if (favOnly) refresh() }
        )
        v.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@StoriesFragment.adapter
            itemAnimator = null
        }
        vm.all.observe(viewLifecycleOwner) { refresh() }
        vm.loaded.observe(viewLifecycleOwner) { refresh() }
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        var l = vm.stories()
        if (favOnly) l = l.filter { Prefs.has("fav", "s${it.key}") }
        if (query.isNotBlank()) l = l.filter { it.title.contains(query.trim(), true) }
        adapter.submitList(l)
        empty.visibility = if (l.isEmpty() && vm.loaded.value == true) View.VISIBLE else View.GONE
        empty.text = when {
            query.isNotBlank() -> "No results"
            favOnly -> "No favourite stories"
            else -> "No stories yet"
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && ::search.isInitialized) { search.close(); backCb.isEnabled = false }
    }
}