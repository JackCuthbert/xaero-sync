package io.github.jackcuthbert.xaerosync.shared

/** Maximum restore points retained per player; null means unlimited. */
data class SnapshotRetention(val maximumPerPlayer: Int?) {
    init {
        require(maximumPerPlayer == null || maximumPerPlayer > 0) {
            "Snapshot retention must be positive or unlimited."
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_PER_PLAYER = 10
        val DEFAULT = SnapshotRetention(DEFAULT_MAXIMUM_PER_PLAYER)
        val UNLIMITED = SnapshotRetention(null)
    }
}
