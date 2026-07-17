package com.flue.launcher.applist.nativeview

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppItemKey

internal class NativeAppListAdapter(
    private val commandSink: (AppListCommand) -> Unit,
    initialConfig: NativeAppListResolvedConfig =
        NativeAppListResolvedConfig.from(NativeAppListConfig()),
) : ListAdapter<NativeAppListBoundRow<Bitmap>, NativeAppListAdapter.AppItemViewHolder>(
    RowDiff,
) {
    private val stableIdRegistry = StableIdRegistry()
    private var config = initialConfig

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        stableIdRegistry.idFor(getItem(position).stableKey)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppItemViewHolder =
        AppItemViewHolder.create(parent, commandSink)

    override fun onBindViewHolder(holder: AppItemViewHolder, position: Int) {
        holder.bind(getItem(position), config)
    }

    override fun onViewRecycled(holder: AppItemViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    fun submitContent(content: NativeAppListContent, onCommitted: () -> Unit = {}) {
        submitList(content.rows, onCommitted)
    }

    fun setConfig(config: NativeAppListResolvedConfig) {
        if (this.config.value == config.value) return
        this.config = config
    }

    fun clearSynchronously() {
        submitList(null)
    }

    fun currentKeysSnapshot(): List<AppItemKey> = currentList.map { it.stableKey }

    fun keyAtPositionOrNull(position: Int): AppItemKey? =
        currentList.getOrNull(position)?.stableKey

    internal class AppItemViewHolder private constructor(
        private val rowView: NativeAppListRowView,
        private val commandSink: (AppListCommand) -> Unit,
    ) : RecyclerView.ViewHolder(rowView) {
        private var boundRow: NativeAppListBoundRow<Bitmap>? = null
        private val clickListener = View.OnClickListener click@ { clickedView ->
            val row = boundRow ?: return@click
            val parent = clickedView.parent as? RecyclerView ?: return@click
            commandSink(
                AppListCommand.Open(
                    key = row.stableKey,
                    origin = rowView.normalizedIconOrigin(parent),
                ),
            )
        }
        private val longClickListener = View.OnLongClickListener longClick@ {
            val row = boundRow ?: return@longClick false
            commandSink(AppListCommand.ShowActions(row.stableKey))
            true
        }

        fun bind(
            row: NativeAppListBoundRow<Bitmap>,
            config: NativeAppListResolvedConfig,
        ) {
            boundRow = row
            rowView.bind(row = row, config = config)
            rowView.setOnClickListener(clickListener)
            rowView.setOnLongClickListener(longClickListener)
        }

        fun clear() {
            boundRow = null
            rowView.clearBinding()
        }

        fun boundKeyOrNull(): AppItemKey? = boundRow?.stableKey

        fun boundRowOrNull(): NativeAppListBoundRow<Bitmap>? = boundRow

        companion object {
            fun create(
                parent: ViewGroup,
                commandSink: (AppListCommand) -> Unit,
            ): AppItemViewHolder {
                val rowView = NativeAppListRowView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                return AppItemViewHolder(rowView, commandSink)
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
        ): Boolean = areNativeAppListRowContentsTheSame(oldItem, newItem)
    }
}
