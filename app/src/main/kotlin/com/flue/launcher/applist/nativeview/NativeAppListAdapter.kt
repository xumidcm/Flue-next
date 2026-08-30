package com.flue.launcher.applist.nativeview

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppItemType
import com.flue.launcher.applist.model.AppListMovePlanner

internal class NativeAppListAdapter(
    private val commandSink: (AppListCommand) -> Unit,
    initialConfig: NativeAppListResolvedConfig =
        NativeAppListResolvedConfig.from(NativeAppListConfig()),
    private val onListCommitted: (itemCount: Int) -> Unit = {},
    private val onDragPreviewOrderChanged: (
        originalOrder: List<AppItemKey>,
        previewOrder: List<AppItemKey>,
    ) -> Unit = { _, _ -> },
    private val onDragPreviewFinished: (committed: Boolean) -> Unit = {},
) : ListAdapter<NativeAppListBoundRow<Bitmap>, NativeAppListAdapter.AppItemViewHolder>(
    RowDiff,
) {
    private val stableIdRegistry = StableIdRegistry()
    private var config = initialConfig
    private var dragPreview: NativeStableKeyDragPreview? = null
    private var dragPreviewOriginalRows: List<NativeAppListBoundRow<Bitmap>> = emptyList()
    private var pendingDragCommitRows: List<NativeAppListBoundRow<Bitmap>>? = null
    private var pendingDragCommitCommand: AppListCommand.Move? = null
    // AsyncListDiffer skips superseded commit callbacks. The token keeps host
    // cleanup pending until whichever later authoritative generation commits.
    private var nextHostPreviewCleanupToken = 0L
    private var pendingHostPreviewCleanupToken: Long? = null
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
        // 拖拽预览进行中来了新的权威内容：**先试着把预览搬过去**，而不是无条件丢弃。
        // 图标异步解码完成会反复 bump iconRevision → 宿主重提交同一顺序的内容；从前
        // 这里直接 discard，previewMove 从此永远 false —— 手势还活着，避让却再也不动，
        // 只能松手重拖（星环上反复拖几下才肯让位就是它）。顺序真的变了（仓库赢过
        // 手势）才照旧丢弃。
        val preservedPreview = reattachDragPreview(content.rows)
        val preservedOrder = if (preservedPreview) {
            dragPreview?.let { it.originalOrderSnapshot to it.currentOrder }
        } else {
            null
        }
        if (preservedPreview == false) {
            // An uncommitted preview is cancelled immediately. A committed one has
            // already dropped its adapter-local state, so its cleanup token is
            // carried by this authoritative submission instead.
            if (discardDragPreview()) {
                pendingHostPreviewCleanupToken = null
                onDragPreviewFinished(false)
            }
        }
        pendingDragCommitRows = null
        pendingDragCommitCommand = null
        optimisticRows = content.rows
        val cleanupToken = pendingHostPreviewCleanupToken
        submitList(content.rows) {
            onListCommitted(content.rows.size)
            finishHostPreviewCleanup(cleanupToken)
            val order = preservedOrder
            if (order != null && dragPreview != null) {
                // 重绑定/新挂载的行要拿回预览位移：把当前虚拟顺序再广播一次，
                // 宿主会把 attached children 重新摆到虚拟格位上。
                onDragPreviewOrderChanged(order.first, order.second)
            }
            onCommitted()
        }
    }

    /**
     * 把活着的拖拽预览迁到新的内容快照上。
     *
     * 只有两情况可迁：新顺序 == 预览的原始顺序（图标解码这类内容级更新的典型形状），
     * 或新顺序 == 预览的当前虚拟顺序（仓库恰好已和预览一致）。其余任何顺序都说明
     * 权威快照自己动了，预览作废、返回 false。迁移只换行快照（新图标、新元数据），
     * 虚拟顺序原样保留，后续 previewMove / finishDragPreview 拿到的都是新内容。
     */
    private fun reattachDragPreview(rows: List<NativeAppListBoundRow<Bitmap>>): Boolean {
        val preview = dragPreview ?: return false
        val original = preview.originalOrderSnapshot
        val current = preview.currentOrder
        if (rows.size != original.size) return false
        val keys = rows.map { it.stableKey }
        if (keys.toSet() != original.toSet()) return false
        if (keys != original && keys != current) return false
        val rowsByKey = rows.associateBy { it.stableKey }
        dragPreviewOriginalRows = original.mapNotNull(rowsByKey::get)
        if (dragPreviewOriginalRows.size != original.size) {
            dragPreviewOriginalRows = emptyList()
            return false
        }
        return true
    }

    fun setConfig(config: NativeAppListResolvedConfig) {
        if (this.config.value == config.value) return
        this.config = config
    }

    fun clearSynchronously() {
        val shouldClearHostPreview =
            discardDragPreview() || pendingHostPreviewCleanupToken != null
        pendingHostPreviewCleanupToken = null
        if (shouldClearHostPreview) onDragPreviewFinished(false)
        pendingDragCommitRows = null
        pendingDragCommitCommand = null
        optimisticRows = emptyList()
        submitList(null) {
            onListCommitted(0)
        }
    }

    fun currentKeysView(): List<AppItemKey> =
        dragPreview?.currentOrder
            ?: pendingDragCommitRows?.let { StableKeyRowList(it) }
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
        pendingDragCommitRows = null
        pendingDragCommitCommand = null
        // A new virtual session takes ownership of any still-visible preview.
        // In-flight callbacks from the previous session must not clear it.
        pendingHostPreviewCleanupToken = null
        onDragPreviewOrderChanged(preview.originalOrderSnapshot, preview.currentOrder)
        return true
    }

    /**
     * Applies one hover to a stable-key-only virtual order.
     *
     * No ListAdapter submission or bind occurs here. The host animates attached
     * children toward virtual slots; one real submit is allowed only after the
     * command owner accepts ACTION_UP.
     */
    fun previewMove(source: AppItemKey, target: AppItemKey): Boolean {
        val preview = dragPreview ?: return false
        if (preview.currentOrder.firstOrNull { it == source } == null) return false
        val nextKeys = preview.moveTo(target) ?: return false
        val rowsByKey = dragPreviewOriginalRows.associateBy { it.stableKey }
        if (nextKeys.any { it !in rowsByKey }) {
            // The authoritative snapshot changed underneath the gesture.
            val hadPreview = discardDragPreview()
            pendingHostPreviewCleanupToken = null
            if (hadPreview) onDragPreviewFinished(false)
            return false
        }
        onDragPreviewOrderChanged(preview.originalOrderSnapshot, nextKeys)
        return true
    }

    /**
     * Prepares one stable-key command. The actual adapter submission is
     * deferred to [completeDragPreview], after the repository accepts it.
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
            pendingDragCommitRows = finalRows
            pendingDragCommitCommand = command
        } else {
            optimisticRows = originalRows
            pendingDragCommitRows = null
            pendingDragCommitCommand = null
            pendingHostPreviewCleanupToken = null
            onDragPreviewFinished(false)
        }
        dragPreview = null
        if (!commit || !validFinalOrder) dragPreviewOriginalRows = emptyList()
        return command
    }

    /**
     * Completes the release transaction. Accepted moves produce exactly one
     * ListAdapter submit; rejected moves only clear the virtual transforms.
     */
    fun completeDragPreview(accepted: Boolean) {
        val rows = pendingDragCommitRows
        val command = pendingDragCommitCommand
        pendingDragCommitRows = null
        pendingDragCommitCommand = null
        if (accepted && rows != null && command != null) {
            optimisticRows = rows
            val cleanupToken = beginHostPreviewCleanup()
            submitList(rows) {
                onListCommitted(rows.size)
                finishHostPreviewCleanup(cleanupToken)
            }
        } else {
            optimisticRows = dragPreviewOriginalRows.takeIf { it.isNotEmpty() }
                ?: optimisticRows
            pendingHostPreviewCleanupToken = null
            onDragPreviewFinished(false)
        }
        dragPreviewOriginalRows = emptyList()
    }

    /**
     * Ends a preview after a structural command was accepted. The current
     * visual order remains in ListAdapter until the repository submits the
     * authoritative folder snapshot; no reverse-to-original Diff is queued.
     */
    fun discardDragPreviewAwaitingAuthoritative() {
        if (pendingHostPreviewCleanupToken == null && hasDragPreviewState()) {
            beginHostPreviewCleanup()
        }
        discardDragPreview()
        pendingDragCommitRows = null
        pendingDragCommitCommand = null
        dragPreviewOriginalRows = emptyList()
    }

    /**
     * Keeps the drag preview alive while a structural folder mutation is
     * being committed, but removes the source/target rows immediately. This
     * prevents the two apps from briefly returning as ordinary rows between
     * ACTION_UP and the repository's authoritative folder snapshot.
     */
    fun previewFolderCommit(
        source: AppItemKey,
        target: AppItemKey,
    ): Boolean {
        if (source == target || dragPreview == null) return false
        val baseRows = dragPreviewOriginalRows.takeIf { it.isNotEmpty() }
            ?: (optimisticRows ?: currentList)
        val rowsByKey = baseRows.associateBy { it.stableKey }
        val rows = dragPreview?.currentOrder
            ?.mapNotNull(rowsByKey::get)
            ?.takeIf { it.size == baseRows.size }
            ?: baseRows
        if (rows.none { it.stableKey == source } || rows.none { it.stableKey == target }) {
            return false
        }
        val sourceRow = rows.firstOrNull { it.stableKey == source } ?: return false
        val targetRow = rows.firstOrNull { it.stableKey == target } ?: return false
        // Preserve only an existing folder row that remains authoritative.
        // app + app creates a brand-new folder stable key, so neither old app
        // may stay as a migration proxy: keeping the target is the exact stale
        // row users saw stuck beneath the newly-created folder icon.
        val sourceIsFolder = sourceRow.entry.type == AppItemType.FOLDER
        val targetIsFolder = targetRow.entry.type == AppItemType.FOLDER
        val removeSource = !sourceIsFolder || targetIsFolder
        val removeTarget = !targetIsFolder
        val nextRows = rows.filterNot { row ->
            (removeSource && row.stableKey == source) ||
                (removeTarget && row.stableKey == target)
        }
        optimisticRows = nextRows
        val cleanupToken = beginHostPreviewCleanup()
        submitList(nextRows) {
            onListCommitted(nextRows.size)
            finishHostPreviewCleanup(cleanupToken)
        }
        // The structural command has already been accepted by the owner. Keep
        // only the optimistic structural snapshot; otherwise currentKeysView()
        // would continue to expose the stale dragPreview order until ACTION_UP.
        dragPreview?.reset()
        dragPreview = null
        pendingDragCommitRows = null
        pendingDragCommitCommand = null
        dragPreviewOriginalRows = emptyList()
        return true
    }

    private fun hasDragPreviewState(): Boolean =
        dragPreview != null ||
            pendingDragCommitRows != null ||
            dragPreviewOriginalRows.isNotEmpty()

    private fun discardDragPreview(): Boolean {
        val hadPreview = hasDragPreviewState()
        dragPreview?.reset()
        dragPreview = null
        dragPreviewOriginalRows = emptyList()
        pendingDragCommitRows = null
        pendingDragCommitCommand = null
        return hadPreview
    }

    private fun beginHostPreviewCleanup(): Long {
        val token = ++nextHostPreviewCleanupToken
        pendingHostPreviewCleanupToken = token
        return token
    }

    private fun finishHostPreviewCleanup(token: Long?) {
        if (token == null || pendingHostPreviewCleanupToken != token) return
        pendingHostPreviewCleanupToken = null
        onDragPreviewFinished(true)
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

    fun rowForKey(key: AppItemKey): NativeAppListBoundRow<Bitmap>? =
        dragPreviewOriginalRows.firstOrNull { it.stableKey == key }
            ?: (optimisticRows ?: currentList).firstOrNull { it.stableKey == key }

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
