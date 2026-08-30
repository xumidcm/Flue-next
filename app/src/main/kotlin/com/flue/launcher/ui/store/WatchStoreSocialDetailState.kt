package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreComment
import com.flue.launcher.watchface.store.WatchStoreAuthSession
import com.flue.launcher.watchface.store.WatchStoreFeatureFlags

internal data class WatchStoreSocialDetailVisibility(
    val showComments: Boolean,
    val allowCommentInput: Boolean,
    val showRating: Boolean,
    val ratingRequiresLogin: Boolean,
    val showFavorite: Boolean,
    val favoriteRequiresLogin: Boolean,
    val showReport: Boolean,
    val reportRequiresLogin: Boolean,
)

/**
 * 详情页社交区的可见性。
 *
 * **「显示」和「能用」是两件事。** 以前收藏 / 举报 / 评分都硬编成 `social && signedIn`，
 * 未登录时那一整块凭空消失，于是「这块表盘能不能收藏、能不能打分」在登录之前根本看不出来
 * （老师原话：没有登录的时候表盘详情也要显示点赞评论等按钮，但点击之后会提示去登录）。
 *
 * 评论区本来就是对的 —— `showComments` 不看登录态，只把输入框换成一句「登录后才能发表评论」。
 * 这里把其余三个拉到同一套规则：**服务端开关决定显示，登录态只决定点下去是干活还是弹「去登录」**。
 * 所以每个动作多一个 `requiresLogin`，而不是把 `show*` 关掉。
 */
internal fun watchStoreSocialDetailVisibility(
    flags: WatchStoreFeatureFlags,
    signedIn: Boolean,
): WatchStoreSocialDetailVisibility {
    val social = flags.socialEnabled
    // 评分还额外受 reviews_enabled 管；收藏与举报只看 social_enabled。
    val showRating = social && flags.reviewsEnabled
    return WatchStoreSocialDetailVisibility(
        showComments = social,
        allowCommentInput = social && flags.commentsEnabled && signedIn,
        showRating = showRating,
        // requiresLogin 必须与 show 同真同假：功能被服务端关掉时它压根没显示，
        // 说「需要登录」是误导，也会让调用方以为登录就能用。
        ratingRequiresLogin = showRating && !signedIn,
        showFavorite = social,
        favoriteRequiresLogin = social && !signedIn,
        showReport = social,
        reportRequiresLogin = social && !signedIn,
    )
}

/** 一条顶级评论 + 挂在它下面的**全部**后代（已扁平化，见 [threadWatchStoreComments]）。 */
internal data class WatchStoreCommentThread(
    val root: WatchStoreComment,
    val replies: List<WatchStoreCommentReply>,
)

/**
 * 一条回复 + 它**直接**父评论的作者名。
 *
 * 扁平化之后「这条在回复谁」就从缩进层级里丢了，只能靠这个标签补回来（上游 Web 端也是
 * 缩进封顶三级之后改用标签，`frontend/src/components/CommentsPanel.jsx:75-77`）。
 * 父评论被管理员隐藏或已删除时给 null —— 那种情况下没有名字可写。
 */
internal data class WatchStoreCommentReply(
    val comment: WatchStoreComment,
    val replyToUsername: String?,
)

internal data class WatchStoreCommentActions(
    val canReply: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

internal fun watchStoreCommentActions(
    comment: WatchStoreComment,
    signedInAuthorId: String?,
): WatchStoreCommentActions = WatchStoreCommentActions(
    canReply = true,
    canEdit = isOwnWatchStoreComment(comment, signedInAuthorId),
    canDelete = isOwnWatchStoreComment(comment, signedInAuthorId),
)

internal fun visibleWatchStoreReplies(
    thread: WatchStoreCommentThread,
    showReplies: Boolean,
    expanded: Boolean,
): List<WatchStoreCommentReply> = when {
    !showReplies -> emptyList()
    expanded -> thread.replies
    else -> thread.replies.take(1)
}

internal fun watchStoreReplyCount(thread: WatchStoreCommentThread): Int = thread.replies.size

/**
 * 建树时最多往上走这么多跳。
 *
 * 上游自己在通知遍历里也加了同样的保险（`api/src/routes/features.js:146` 的 `hops++ < 20`），
 * 因为 DB 侧不存在「不许成环」的约束。
 */
private const val MAX_COMMENT_THREAD_HOPS = 20

/**
 * 把上游的扁平评论列表折成「顶级评论 + 它的全部后代」。
 *
 * 上游 `GET /watchfaces/:id/comments` 给的是**扁平列表 + `parent_comment_id`**，一次全量、
 * `created_at ASC`、没有分页也没有内联 replies（`api/src/routes/features.js:88-95`）。
 *
 * **任意深度一律摊平到所属顶级 thread 的 replies 里**，不做递归缩进：`parent_comment_id`
 * 没有「父必须是顶级」的约束（`api/migrations/004_creators_modal.sql:11`），链可以无限深，
 * 而手表屏宽只有 230dp —— 缩进到第三层一行就只剩几个字了。层级信息改由
 * [WatchStoreCommentReply.replyToUsername] 以「回复 @某人」的形式承载。
 *
 * 以前这里只认「parent 恰好是顶级评论」的一层：parent 是另一条回复、或者 parent 被管理员
 * 隐藏时，那条回复会被提拔成顶级评论 —— 正是老师报的「只会把前两条回复当那个评论的回复，
 * 剩下的会直接被当作普通评论展示」。
 */
internal fun threadWatchStoreComments(
    comments: List<WatchStoreComment>,
): List<WatchStoreCommentThread> {
    if (comments.isEmpty()) return emptyList()
    // 先按 id 去重：结果里的 root.id 直接当 LazyColumn 的 key 用，重复 key 会当场抛异常。
    val unique = comments.distinctBy(WatchStoreComment::id)
    val byId = unique.associateBy(WatchStoreComment::id)
    val rootIdByComment = unique.associate { it.id to resolveThreadRootId(it, byId) }
    val repliesByRoot = HashMap<String, MutableList<WatchStoreCommentReply>>()
    unique.forEach { comment ->
        val rootId = rootIdByComment.getValue(comment.id)
        // rootId == 自己 → 它就是顶级，不进任何人的 replies。
        if (rootId == comment.id) return@forEach
        repliesByRoot.getOrPut(rootId) { mutableListOf() }.add(
            WatchStoreCommentReply(
                comment = comment,
                replyToUsername = comment.parentCommentId
                    ?.let(byId::get)
                    ?.authorUsername
                    ?.takeIf(String::isNotBlank),
            ),
        )
    }
    // 顶级与回复都保持服务端给的顺序（最旧在前），不要在客户端重排。
    return unique
        .filter { rootIdByComment.getValue(it.id) == it.id }
        .map { root -> WatchStoreCommentThread(root, repliesByRoot[root.id].orEmpty()) }
}

/**
 * 顺着 `parentCommentId` 往上找这条评论所属的顶级评论 id。
 *
 * 三种情况都归成「自己就是顶级」：没有 parent、parent 不在这批数据里（被隐藏或被删）、
 * 以及**父子成环**。环是脏数据而不是正常形态，但上游没有任何约束能阻止它写进来，所以这里
 * 靠 [MAX_COMMENT_THREAD_HOPS] 兜住 —— 宁可把评论摆错位置，也不能死循环、更不能丢评论。
 */
private fun resolveThreadRootId(
    comment: WatchStoreComment,
    byId: Map<String, WatchStoreComment>,
): String {
    var current = comment
    var hops = 0
    while (hops++ < MAX_COMMENT_THREAD_HOPS) {
        val parentId = current.parentCommentId ?: return current.id
        val parent = byId[parentId] ?: return current.id
        // 已经绕回起点：这条评论在环上，让它自成一条顶级评论。
        if (parent.id == comment.id) return comment.id
        current = parent
    }
    // 跳数用尽：要么是环（起点不在环上），要么真有 20 层以上的链。同样自成顶级。
    return comment.id
}

/**
 * 归属判断用**不可变的 `user.id`**，不再比用户名。
 *
 * 用户名是可变的展示名；更要紧的是 `PUT /my/comments/:id` 的响应里根本没有 user 字段
 * （`api/src/routes/my.js:746-751` 只 `RETURNING id, content, created_at, updated_at`），
 * 一旦拿用户名比字符串，编辑过的评论立刻「不算自己的」，编辑与删除图标一起消失。
 */
internal fun isOwnWatchStoreComment(
    comment: WatchStoreComment,
    signedInAuthorId: String?,
): Boolean = !signedInAuthorId.isNullOrBlank() && comment.authorId == signedInAuthorId

/**
 * 把 `PUT /my/comments/:id` 的响应合并回本地那条评论。
 *
 * **只能合 content。** 上游那条只 `RETURNING id, content, created_at, updated_at`
 * （`api/src/routes/my.js:746-751`），既没有 `user` 也没有 `parent_comment_id`。整条替换的话
 * 解析出来的 `parentCommentId` 是 null、`authorId` / `authorUsername` 是空串 —— 编辑过的回复
 * 会当场脱离原评论跳到顶级，作者名变空，编辑与删除按钮消失。这是「剩下的变成普通评论」
 * 最容易复现的一条路径。
 */
internal fun mergeEditedWatchStoreComment(
    original: WatchStoreComment,
    response: WatchStoreComment,
): WatchStoreComment = original.copy(content = response.content)

/** Fills the sparse POST response with the current account's identity immediately. */
internal fun enrichPostedWatchStoreComment(
    posted: WatchStoreComment,
    session: WatchStoreAuthSession,
): WatchStoreComment = posted.copy(
    authorUsername = session.username,
    authorAvatarUrl = session.avatarUrl,
    authorAvatarVersion = session.avatarVersion,
)

/**
 * 删掉一条评论之后本地该剩下什么：**整棵子树都要砍**。
 *
 * DB 侧是 `parent_comment_id UUID REFERENCES comments(id) ON DELETE CASCADE`
 * （`api/migrations/004_creators_modal.sql:11`），服务端删一条会连带删掉它的全部后代。
 * 本地只砍一层的话，孙子辈会变成 parent 不存在的孤儿，在 [threadWatchStoreComments] 里
 * 各自升成顶级评论 —— 看起来像「删了一条反而冒出一堆新评论」。
 */
internal fun commentsAfterWatchStoreCommentDeletion(
    comments: List<WatchStoreComment>,
    deletedId: String,
): List<WatchStoreComment> {
    val childrenByParent = comments.groupBy(WatchStoreComment::parentCommentId)
    val doomed = HashSet<String>()
    val pending = ArrayDeque<String>()
    pending.addLast(deletedId)
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        // add 返回 false 说明这条已经在名单里 —— 父子成环时靠它收敛。
        if (!doomed.add(id)) continue
        childrenByParent[id]?.forEach { child -> pending.addLast(child.id) }
    }
    return comments.filterNot { it.id in doomed }
}

internal fun favoriteCountAfterToggle(
    current: Long,
    wasFavorite: Boolean,
    isFavorite: Boolean,
): Long = when {
    wasFavorite == isFavorite -> current.coerceAtLeast(0)
    isFavorite -> current.coerceAtLeast(0) + 1
    else -> (current - 1).coerceAtLeast(0)
}
