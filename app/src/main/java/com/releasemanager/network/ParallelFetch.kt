package com.releasemanager.network

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Fetches [items] up to [concurrency] at a time but hands the results to [consume] strictly in the order of
 * [items] — what a ZIP or a single output file needs. [concurrency] also caps how many results wait in memory:
 * a slot is only given back once [consume] has finished with its result, and slots are taken in list order, so
 * the item [consume] is waiting for always already holds one (no deadlock).
 *
 * The first failure (from [fetch] or [consume]) cancels everything that is still running and is rethrown.
 */
suspend fun <T, R> fetchOrdered(
    items: List<T>,
    concurrency: Int,
    fetch: suspend (T) -> R,
    consume: suspend (T, R) -> Unit
) {
    if (items.isEmpty()) return
    coroutineScope {
        val slots = Semaphore(concurrency.coerceAtLeast(1))
        val queue = Channel<Pair<T, Deferred<R>>>(Channel.UNLIMITED)
        launch {
            try {
                for (item in items) {
                    slots.acquire()
                    queue.send(item to async(Dispatchers.IO) { fetch(item) })
                }
            } finally {
                queue.close()
            }
        }
        for ((item, pending) in queue) {
            val result = pending.await()
            consume(item, result)
            slots.release()
        }
    }
}
