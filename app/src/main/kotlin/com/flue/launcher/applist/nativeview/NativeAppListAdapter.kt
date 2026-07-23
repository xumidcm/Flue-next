package com.flue.launcher.applist.nativeview

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListMovePlanner

internal class NativeAppListAdapter(
    private val commandSink: (AppListCommand) -> Unit,
    initialConfig: NativeAppListResolvedConfig =
        NativeAppListResolvedConfig.from(NativeAppListConfig()),
    private val onListCommitted: (itemCount: Int) -> Unit = {},
) : ListAdapter<NativeAppListBoundRow<Bitmap>, NativeAppListAdapter.AppItemViewHolder>(
    RowDiff,
) {
    private val stableIdRegistry = StableIdRegistry()
    private var config = initialConfig
    private var dragPreview: NativeStableKeyDragPreview? = null
    private var dragPreviewOriginalRows: List<NativeAppListBoundRow<Bitmap>> = emptyList()
    /**
     * Rows most recently requested by either the repository or a local drag.
     * ListAdapter applies DiffUtil asynchronously; keeping this small main-
     * thread snapshot prevents a second gesture from capturing the previous
     * order while the first diff is still in flight.
     */
    private var optimisticRows: List<NativeAppListBoundRow<Bitmap>>? = null
    private val currentKeyOrder = object : AbstractList<AppItemKey>() {
        override val size: Int
            get() = currentList.size

        override fun get(index: Int): AppItemKey = currentList[index].stableKey
    }

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
        // A repository snapshot supersedes a local drag preview.  Drop the
        // preview before submitting the authoritative rows so an async
        // DiffUtil result can never resurrect the old visual order.
        discardDragPreview()
        optimisticRows = content.rows
        submitList(content.rows) {
            onListCommitted(content.rows.size)
            onCommitted()
        }
    }

    fun setConfig(config: NativeAppListResolvedConfig) {
        if (this.config.value == config.value) return
        this.config = config
    }

    fun clearSynchronously() {
        discardDragPreview()
        optimisticRows = emptyList()
        submitList(null) {
            onListCommitted(0)
        }
    }

    fun currentKeysView(): List<AppItemKey> =
        dragPreview?.currentOrder
            ?: optimisticRows?.let { StableKeyRowList(it) }
            ?: currentKeyOrder

    /** Starts an adapter-local stable-key reorder session. */
    fun beginDragPreview(source: AppItemKey): Boolean {
        if (dragPreview != null) return false
        val rows = (optimisticRows ?: currentList).toList()
        val preview = NativeStableKeyDragPreview()
        if (!preview.begin(source, rows.map { it.stableKey })) {
            return false
        }
        dragPreview = preview
        dragPreviewOriginalRows = rows
        return true
    }

    /** Applies one hover to the local adapter order without dispatching a command. */
    fun previewMove(source: AppItemKey, target: AppItemKey): Boolean {
        val preview = dragPreview ?: return false
        if (preview.currentOrder.firstOrNull { it == source } == null) return false
        val nextKeys = preview.moveTo(target) ?: return false
        val rowsByKey = dragPreviewOriginalRows.associateBy { it.stableKey }
        val nextRows = nextKeys.mapNotNull(rowsByKey::get)
        if (nextRows.size != nextKeys.size) {
            // The authoritative snapshot changed underneath the gesture.
            // Restore the captured authoritative order instead of binding a
            // partial preview; any newer repository submission still wins by
            // AsyncListDiffer generation.
            val originalRows = dragPreviewOriginalRows
            optimisticRows = originalRows
            submitList(originalRows)
            preview.reset()
            dragPreview = null
            dragPreviewOriginalRows = emptyList()
            return false
        }
        submitList(nextRows)
        optimisticRows = nextRows
        return true
    }

    /**
     * Ends the local reorder session and returns one command only for a
     * committed release.  Cancellation restores the original visual rows.
     */
    fun finishDragPreview(commit: Boolean): AppListCommand.Move? {
        val preview = dragPreview ?: return null
        val originalRows = dragPreviewOriginalRows
        val rowsByKey = originalRows.associateBy { it.stableKey }
        val mappedFinalRows = preview.currentOrder.mapNotNull(rowsByKey::get)
        val finalRows = mappedFinalRows.takeIf {
            it.size == preview.currentOrder.size
        } ?: originalRows
        val validFinalOrder = mappedFinalRows.size == preview.currentOrder.size
        val command = preview.finish(commit && validFinalOrder)
        if (commit && validFinalOrder) {
            optimisticRows = finalRows
        } else {
            optimisticRows = originalRows
            submitList(originalRows)
        }
        dragPreview = null
        dragPreviewOriginalRows = emptyList()
        return command
    }

    private fun discardDragPreview() {
        dragPreview?.reset()
        dragPreview = null
        dragPreviewOriginalRows = emptyList()
    }

    fun planMove(
        source: AppItemKey,
        target: AppItemKey,
    ): AppListCommand.Move? = AppListMovePlanner.fromKeys(
        keys = currentKeysView(),
        movedKey = source,
        targetKey = target,
    )

    fun planFolder(
        source: AppItemKey,
        target: AppItemKey,
    ): AppListCommand.CreateFolder? = AppListMovePlanner.folderFromKeys(
        keys = currentKeysView(),
        sourceKey = source,
        targetKey = target,
    )

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

    /** Non-copying key view over an immutable row snapshot. */
    private class StableKeyRowList(
        private val rows: List<NativeAppListBoundRow<Bitmap>>,
    ) : AbstractList<AppItemKey>() {
        override val size: Int
            get() = rows.size

        override fun get(index: Int): AppItemKey = rows[index].stableKey
    }
}
