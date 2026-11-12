package com.dreamdisplays.core.queue

/**
 * Per-key token buckets: a key may spend up to [capacity] at once, refilled at [refillPerSecond]. Cheap enough to sit
 * in front of every client packet of a kind (a map lookup and a little arithmetic). Thread-safe. Memory is bounded by
 * [maxKeys]: past it, buckets that refilled completely (the same as absent) are dropped, and if that is not enough,
 * all of them.
 */
class TokenBuckets(
    private val capacity: Double,
    refillPerSecond: Double,
    private val maxKeys: Int = 4096,
) {
    private class Bucket(var tokens: Double, var atMs: Long)

    private val refillPerMs = refillPerSecond / 1000.0
    private val buckets = HashMap<Any, Bucket>()

    /** Spends [cost] from [key]'s bucket at [nowMs]; false (nothing spent) when it does not hold that much. */
    @Synchronized
    fun tryTake(key: Any, nowMs: Long, cost: Double = 1.0): Boolean {
        val bucket = buckets[key] ?: run {
            if (buckets.size >= maxKeys) prune(nowMs)
            Bucket(capacity, nowMs).also { buckets[key] = it }
        }
        refill(bucket, nowMs)
        if (bucket.tokens < cost) return false
        bucket.tokens -= cost
        return true
    }

    @Synchronized
    fun forget(key: Any) {
        buckets.remove(key)
    }

    @Synchronized
    fun size(): Int = buckets.size

    private fun refill(bucket: Bucket, nowMs: Long) {
        // A clock that went back refills nothing and does not move the bucket's time back either
        if (nowMs <= bucket.atMs) return
        bucket.tokens = minOf(capacity, bucket.tokens + (nowMs - bucket.atMs) * refillPerMs)
        bucket.atMs = nowMs
    }

    private fun prune(nowMs: Long) {
        buckets.values.removeIf { refill(it, nowMs); it.tokens >= capacity }
        if (buckets.size >= maxKeys) buckets.clear()
    }
}
