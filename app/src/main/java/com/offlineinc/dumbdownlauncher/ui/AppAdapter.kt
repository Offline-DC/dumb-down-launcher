// ui/AppAdapter.kt
package com.offlineinc.dumbdownlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.model.AppItem
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

class AppAdapter(
    private val recyclerView: RecyclerView,
    private val items: List<AppItem>,
    private val getSelectedIndex: () -> Int,
    private val setSelectedIndex: (Int) -> Unit,
    private val onActivate: (Int) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.rowRoot)
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_app, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.title.text = item.label

        holder.title.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            holder.title.context,
            R.font.syne_mono
        )

        val selected = position == getSelectedIndex()
        if (selected) {
            holder.title.setTextColor(0xFF000000.toInt())
        } else {
            holder.title.setTextColor(0xFFFFFFFF.toInt())
        }
        val isSpecialIcon =
            item.packageName == com.offlineinc.dumbdownlauncher.ALL_APPS ||
                    item.packageName == com.offlineinc.dumbdownlauncher.NOTIFICATIONS ||
                    item.packageName == "com.ubercab" ||
                    item.packageName == "com.offline.uberlauncher"

        // Only tint our custom vector icons
        if (isSpecialIcon) {
            holder.icon.setColorFilter(
                if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        } else {
            holder.icon.clearColorFilter()
        }

        holder.root.setBackgroundColor(if (selected) 0xFFFFD400.toInt() else 0x00000000)
        val isSpecial = item.packageName == com.offlineinc.dumbdownlauncher.ALL_APPS ||
            item.packageName == com.offlineinc.dumbdownlauncher.NOTIFICATIONS
        holder.root.alpha = if (item.launchComponent != null || isSpecial) 1.0f else 0.4f

        holder.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val old = getSelectedIndex()
                if (old != position) {
                    setSelectedIndex(position)
                    notifyItemChanged(old)
                    notifyItemChanged(position)
                }
                recyclerView.scrollToPosition(position)
            }
        }

        holder.root.setOnClickListener {
            val old = getSelectedIndex()
            if (old != position) {
                setSelectedIndex(position)
                notifyItemChanged(old)
                notifyItemChanged(position)
            }
            onActivate(position)
        }
    }

    private val grayscaleFilter: ColorMatrixColorFilter by lazy {
        val matrix = ColorMatrix()
        matrix.setSaturation(0f)
        ColorMatrixColorFilter(matrix)
    }

    override fun getItemCount(): Int = items.size
}
