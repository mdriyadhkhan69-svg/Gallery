package com.example.gallery

import android.graphics.Color
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

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

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
            showDarkMenu(it, listOf("Create story", "Hide content", "Mark all as viewed", "Refresh")) { i ->
                when (i) {
                    0 -> askName(requireContext(), "New story", "") { n ->
                        startActivity(GridActivity.intent(requireContext(), "pick", "story", n))
                    }
                    1 -> hideDialog()
                    2 -> {
                        Prefs.setSet("viewed", Prefs.getSet("viewed") + vm.stories().map { st -> st.key })
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    }
                    else -> vm.load()
                }
            }
        }

        adapter = StoryAdapter(
            onClick = { st ->
                if (st.key.startsWith("u")) startActivity(GridActivity.intent(requireContext(), "ustory", st.key.substring(1), st.title))
                else startActivity(GridActivity.intent(requireContext(), "month", st.key, st.title))
            },
            onHeartChanged = { if (favOnly) refresh() },
            onLong = { st -> storyOptions(st) }
        )
        v.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@StoriesFragment.adapter
            itemAnimator = null
        }
        vm.all.observe(viewLifecycleOwner) { refresh() }
        vm.loaded.observe(viewLifecycleOwner) { refresh() }
    }

    // ---------- Long-press options ----------
    private fun storyMedia(s: Story): List<Media> {
        val all = vm.all.value.orEmpty()
        return if (s.key.startsWith("u")) {
            val ids = CustomStories.get(s.key.substring(1).toLong())?.ids ?: emptySet()
            all.filter { it.id in ids }
        } else all.filter { monthKey(it.date) == s.key }
    }

    private fun storyOptions(s: Story) {
        val custom = s.key.startsWith("u")
        val labels = if (custom) arrayOf("Slideshow", "Rename", "Delete story", "Hide")
        else arrayOf("Slideshow", "Hide")
        MaterialAlertDialogBuilder(requireContext()).setTitle(s.title)
            .setItems(labels) { _, w ->
                when (labels[w]) {
                    "Slideshow" -> {
                        val l = storyMedia(s)
                        if (l.isNotEmpty()) {
                            Session.items = l
                            startActivity(ViewerActivity.intent(requireContext(), 0, true))
                        }
                    }
                    "Rename" -> askName(requireContext(), "Rename story", s.title) { n ->
                        CustomStories.rename(s.key.substring(1).toLong(), n); vm.publish()
                    }
                    "Delete story" -> MaterialAlertDialogBuilder(requireContext())
                        .setMessage("Delete this story? Photos will NOT be deleted.")
                        .setPositiveButton("Delete") { _, _ ->
                            CustomStories.delete(s.key.substring(1).toLong()); vm.publish()
                        }.setNegativeButton("Cancel", null).show()
                    "Hide" -> {
                        Prefs.add("hiddenStories", s.key); vm.publish()
                        toast("Hidden. Menu > Hide content theke ferot ante paren")
                    }
                }
            }.show()
    }

    /** Hide content: tick deya story hide hobe, tick tule nile abar ashbe */
    private fun hideDialog() {
        val all = vm.allStories()
        if (all.isEmpty()) { toast("No stories"); return }
        val hidden = Prefs.getSet("hiddenStories")
        val checked = all.map { it.key in hidden }.toBooleanArray()
        MaterialAlertDialogBuilder(requireContext()).setTitle("Hide content")
            .setMultiChoiceItems(all.map { it.title }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Done") { _, _ ->
                Prefs.setSet("hiddenStories", all.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet())
                vm.publish()
            }
            .setNegativeButton("Cancel", null).show()
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