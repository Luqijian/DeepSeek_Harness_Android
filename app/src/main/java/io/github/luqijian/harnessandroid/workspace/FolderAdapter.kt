package io.github.luqijian.harnessandroid.workspace

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.luqijian.harnessandroid.R
import java.io.File

class FolderAdapter(
    private val onOpen: (File) -> Unit,
) : RecyclerView.Adapter<FolderAdapter.Holder>() {
    private val items = mutableListOf<File>()

    fun submit(files: List<File>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = items[position]
        holder.name.text = file.name + if (file.canRead() && file.canWrite()) "" else "  (只读)"
        holder.itemView.setOnClickListener { onOpen(file) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
    }
}
