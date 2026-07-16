package com.flue.launcher.applist.nativeview

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand

internal class NativeAppListAdapter(
    private val commandSink: (AppListCommand) -> Unit,
) : ListAdapter<NativeAppListBoundRow<Bitmap>, NativeAppListAdapter.AppItemViewHolder>(
    RowDiff,
) {
    private val stableIdRegistry = StableIdRegistry()
    private var config = NativeAppListConfig().legalized()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        stableIdRegistry.idFor(getItem(position).stableKey)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppItemViewHolder =
        AppItemViewHolder.create(parent)

    override fun onBindViewHolder(holder: AppItemViewHolder, position: Int) {
        holder.bind(getItem(position), config, commandSink)
    }

    override fun onViewRecycled(holder: AppItemViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    fun submitContent(content: NativeAppListContent) {
        submitList(content.rows)
    }

    fun setConfig(config: NativeAppListConfig) {
        val next = config.legalized()
        if (this.config == next) return
        this.config = next
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun clear() {
        submitList(emptyList())
    }

    internal class AppItemViewHolder private constructor(
        private val rowView: NativeAppListRowView,
    ) : RecyclerView.ViewHolder(rowView) {
        fun bind(
            row: NativeAppListBoundRow<Bitmap>,
            config: NativeAppListConfig,
            commandSink: (AppListCommand) -> Unit,
        ) {
            rowView.bind(
                row = row,
                config = config,
                clickListener = click@ { clickedView ->
                    val parent = clickedView.parent as? RecyclerView ?: return@click
                    commandSink(
                        AppListCommand.Open(
                            key = row.stableKey,
                            origin = rowView.normalizedIconOrigin(parent),
                        ),
                    )
                },
            )
        }

        fun clear() {
            rowView.clearBinding()
        }

        companion object {
            fun create(parent: ViewGroup): AppItemViewHolder {
                val rowView = NativeAppListRowView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                return AppItemViewHolder(rowView)
            }
        }
    }

    private object RowDiff : DiffUtil.ItemCallback<NativeAppListBoundRow<Bitmap>>() {
        override fun areItemsTheSame(
            oldItem: NativeAppListBoundRow<Bitmap>,
            newItem: NativeAppListBoundRow<Bitmap>,
        ): Boolean = oldItem.stableKey == newItem.stableKey

        override fun areContentsTheSame(
            oldItem: NativeAppListBoundRow<Bitmap>,
            newItem: NativeAppListBoundRow<Bitmap>,
        ): Boolean =
            oldItem.snapshotGeneration == newItem.snapshotGeneration &&
                oldItem.entry == newItem.entry &&
                oldItem.icon === newItem.icon
    }
}
