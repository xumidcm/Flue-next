package com.flue.launcher.watchface.standard

/** Small allocation-free wall-clock budget used by main-thread render work. */
internal class WatchmakerFrameBudget(
    private val budgetNanos: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var deadlineNanos = Long.MIN_VALUE

    fun start() {
        val startNanos = nowNanos()
        deadlineNanos = if (budgetNanos <= 0L) {
            startNanos
        } else if (startNanos > Long.MAX_VALUE - budgetNanos) {
            Long.MAX_VALUE
        } else {
            startNanos + budgetNanos
        }
    }

    fun hasTimeRemaining(): Boolean =
        deadlineNanos != Long.MIN_VALUE && nowNanos() < deadlineNanos
}
