package com.offlineinc.dumbdownlauncher.notifications.ui

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
        setSelectionActive(false)
    }

    fun restoreSelectionHighlight() {
        setSelectionActive(true)
    }

    fun submit(newItems: List<NotificationItem>) {
        items.clear()
        items.addAll(newItems)

        if (items.isEmpty()) {
            // ✅ never allow -1
            selectedIndex = 0
            hasActiveSelection = false
        } else {
            selectedIndex = selectedIndex.coerceIn(0, items.lastIndex)
            hasActiveSelection = true
        }

        notifyDataSetChanged()
    }

    /**
     * @return true if selection actually moved
     */
    fun moveSelection(delta: Int): Boolean {
        if (items.isEmpty()) return false

        val old = selectedIndex.coerceIn(0, items.lastIndex)
        val newIndex = (old + delta).coerceIn(0, items.lastIndex)

        if (newIndex == old) return false

        selectedIndex = newIndex
        notifyItemChanged(old)
        notifyItemChanged(selectedIndex)
        return true
    }

    fun activateSelected() {
        if (items.isEmpty()) return
        val idx = selectedIndex.coerceIn(0, items.lastIndex)
        onClick(items[idx])
    }

    fun longPressSelected() {
        if (items.isEmpty()) return
        val idx = selectedIndex.coerceIn(0, items.lastIndex)
        onLongPress(items[idx])
    }

    fun getSelectedIndex(): Int {
        // ✅ never return -1
        if (items.isEmpty()) return 0
        return selectedIndex.coerceIn(0, items.lastIndex)
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

        val font = ResourcesCompat.getFont(holder.title.context, R.font.syne_mono)
        holder.title.typeface = font
        holder.text.typeface = font

        val selected = hasActiveSelection && position == getSelectedIndex()

        holder.root.setBackgroundColor(
            if (selected) 0xFFFFD400.toInt() else 0x00000000
        )

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

    fun setSelection(index: Int) {
        if (items.isEmpty()) return
        val old = selectedIndex.coerceIn(0, items.lastIndex)
        selectedIndex = index.coerceIn(0, items.lastIndex)
        notifyItemChanged(old)
        notifyItemChanged(selectedIndex)
    }

    fun setSelectionActive(active: Boolean) {
        if (items.isEmpty()) {
            hasActiveSelection = false
            return
        }
        val old = selectedIndex.coerceIn(0, items.lastIndex)
        hasActiveSelection = active
        notifyItemChanged(old)
    }
}