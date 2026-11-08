package com.wirelessalien.android.moviedb.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wirelessalien.android.moviedb.data.Tag
import com.wirelessalien.android.moviedb.databinding.ItemTagSyncBinding

class TagSyncAdapter(
    private val tags: List<Tag>,
    private val selectedTags: MutableSet<Long>
) : RecyclerView.Adapter<TagSyncAdapter.TagViewHolder>() {

    inner class TagViewHolder(private val binding: ItemTagSyncBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tag: Tag) {
            binding.checkboxTag.text = tag.name
            binding.checkboxTag.isChecked = selectedTags.contains(tag.id)
            binding.checkboxTag.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedTags.add(tag.id)
                } else {
                    selectedTags.remove(tag.id)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val binding = ItemTagSyncBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TagViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        holder.bind(tags[position])
    }

    override fun getItemCount(): Int = tags.size
}
