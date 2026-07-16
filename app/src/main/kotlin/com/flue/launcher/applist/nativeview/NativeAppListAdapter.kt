package com.flue.launcher.applist.nativeview

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppListEntry
import com.flue.launcher.applist.model.AppListSnapshot

internal class NativeAppListAdapter(
    private val commandSink: (AppListCommand) -> Unit,
) : ListAdapter<AppListEntry, NativeAppListAdapter.AppItemViewHolder>(EntryDiff) {
    private val stableIdRegistry = StableIdRegistry()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = stableIdRegistry.idFor(getItem(position).key)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppItemViewHolder =
        AppItemViewHolder.create(parent)

    override fun onBindViewHolder(holder: AppItemViewHolder, position: Int) {
        holder.bind(getItem(position), commandSink)
    }

    fun submitSnapshot(snapshot: AppListSnapshot) {
        submitList(snapshot.entries)
    }

    fun clear() {
        submitList(emptyList())
    }

    internal class AppItemViewHolder private constructor(
        itemView: View,
        private val iconView: ImageView,
        private val labelView: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(
            entry: AppListEntry,
            commandSink: (AppListCommand) -> Unit,
        ) {
            iconView.setImageResource(android.R.drawable.sym_def_app_icon)
            labelView.text = entry.label
            itemView.contentDescription = entry.label
            itemView.setOnClickListener { clickedView ->
                val parent = clickedView.parent as? RecyclerView
                    ?: return@setOnClickListener
                commandSink(
                    AppListCommand.Open(
                        key = entry.key,
                        origin = normalizedChildCenter(
                            childLeft = clickedView.x,
                            childTop = clickedView.y,
                            childWidth = clickedView.width,
                            childHeight = clickedView.height,
                            viewportWidth = parent.width,
                            viewportHeight = parent.height,
                        ),
                    )
                )
            }
        }

        companion object {
            fun create(parent: ViewGroup): AppItemViewHolder {
                val context = parent.context
                val density = context.resources.displayMetrics.density
                fun Int.dp(): Int = (this * density).toInt()

                val icon = ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp()).apply {
                        marginEnd = 12.dp()
                    }
                }
                val label = TextView(context).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    minimumHeight = 64.dp()
                    setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    addView(icon)
                    addView(label)
                }
                return AppItemViewHolder(
                    itemView = row,
                    iconView = icon,
                    labelView = label,
                )
            }
        }
    }

    private object EntryDiff : DiffUtil.ItemCallback<AppListEntry>() {
        override fun areItemsTheSame(oldItem: AppListEntry, newItem: AppListEntry): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: AppListEntry, newItem: AppListEntry): Boolean =
            oldItem == newItem
    }
}
