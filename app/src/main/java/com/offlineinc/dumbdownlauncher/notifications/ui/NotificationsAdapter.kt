package com.offlineinc.dumbdownlauncher.notifications.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem

class NotificationsAdapter(
    private val onClick: (NotificationItem) -> Unit,
    private val onLongPress: (NotificationItem) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.VH>() {

    private val items = mutableListOf<NotificationItem>()
    private var selectedIndex = 0

    private var hasActiveSelection = true

    fun clearSelectionHighlight() {
        val old = selectedIndex
        hasActiveSelection = false
        notifyItemChanged(old)
    }

    fun restoreSelectionHighlight() {
        hasActiveSelection = true
        notifyItemChanged(selectedIndex)
    }

    fun submit(newItems: List<NotificationItem>) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = 0.coerceAtMost(items.lastIndex)
        notifyDataSetChanged()
    }

    fun moveSelection(delta: Int) {
        if (items.isEmpty()) return
        val old = selectedIndex
        selectedIndex = (selectedIndex + delta).coerceIn(0, items.lastIndex)
        notifyItemChanged(old)
        notifyItemChanged(selectedIndex)
    }

    fun activateSelected() {
        if (items.isNotEmpty()) onClick(items[selectedIndex])
    }

    fun longPressSelected() {
        if (items.isNotEmpty()) onLongPress(items[selectedIndex])
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.notifRowRoot)
        val title: TextView = view.findViewById(R.id.notifTitle)
        val text: TextView = view.findViewById(R.id.notifText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_notification, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.text.text = item.text

        // Apply launcher font
        val font = ResourcesCompat.getFont(holder.title.context, R.font.syne_mono)
        holder.title.typeface = font
        holder.text.typeface = font

        val selected = hasActiveSelection && position == selectedIndex

        // Background highlight
        holder.root.setBackgroundColor(
            if (selected) 0xFFFFD400.toInt() else 0x00000000
        )

        // Text color inversion
        holder.title.setTextColor(
            if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        )
        holder.text.setTextColor(
            if (selected) 0xFF000000.toInt() else 0xFFAAAAAA.toInt()
        )

        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onLongPress(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun getSelectedIndex(): Int = selectedIndex
}
