package dev.keyrelay.core

/**
 * targetForeground = anyTarget && (lastTrueWasTarget || !anyOther): another app's foreground event wins over the target,
 * and a window that opened on top hands focus back with its own false event (spec "On plan kurali").
 */
class ForegroundTracker(val isTarget: (uid: Int) -> Boolean) {
    private val foregroundPids = HashMap<Int, Int>()
    private var lastTrueWasTarget = true

    var targetForeground: Boolean = false
        private set

    /** Takes target pids only: the top-app cgroup also lists processes without foreground activities. */
    fun seed(pidToUid: Map<Int, Int>) {
        foregroundPids.clear()
        for ((pid, uid) in pidToUid) if (isTarget(uid)) foregroundPids[pid] = uid
        lastTrueWasTarget = true
        recompute()
    }

    fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean): Boolean {
        if (foreground) {
            foregroundPids[pid] = uid
            lastTrueWasTarget = isTarget(uid)
        } else {
            foregroundPids.remove(pid)
        }
        return recompute()
    }

    fun onProcessDied(pid: Int): Boolean {
        foregroundPids.remove(pid)
        return recompute()
    }

    private fun recompute(): Boolean {
        var anyTarget = false
        var anyOther = false
        for (uid in foregroundPids.values) if (isTarget(uid)) anyTarget = true else anyOther = true
        val now = anyTarget && (lastTrueWasTarget || !anyOther)
        val changed = now != targetForeground
        targetForeground = now
        return changed
    }
}
