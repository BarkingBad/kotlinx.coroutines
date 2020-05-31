/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.flow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.internal.*

/**
 * A command emitted by [SharingStarted] implementation to control the sharing coroutine in
 * [shareIn] and [stateIn] operators.
 */
@ExperimentalCoroutinesApi
public enum class SharingCommand {
    /**
     * Start the sharing coroutine.
     */
    START,

    /**
     * Stop the sharing coroutine.
     */
    STOP,

    /**
     * Stop the sharing coroutine and [reset buffer][MutableSharedFlow.resetBuffer] of the shared flow.
     */
    STOP_AND_RESET_BUFFER
}

/**
 * A strategy for starting and stopping sharing coroutine in [shareIn] and [stateIn] operators.
 */
@ExperimentalCoroutinesApi
public interface SharingStarted {
    public companion object {
        /**
         * Sharing is started immediately and never stops.
         */
        @ExperimentalCoroutinesApi
        public val Eagerly: SharingStarted = StartedEagerly() // always init because it is a default, likely needed

        /**
         * Sharing is started when the first subscriber appears and never stops.
         */
        @ExperimentalCoroutinesApi
        public val Lazily: SharingStarted by lazy { StartedLazily() }

        /**
         * Sharing is started when the first subscriber appears, immediately stops when the last
         * subscriber disappears, keeping the replay cache forever.
         */
        @ExperimentalCoroutinesApi
        public val WhileSubscribed: SharingStarted by lazy { StartedWhileSubscribed(0L, Long.MAX_VALUE) }

        /**
         * Sharing is started when the first subscriber appears, stops when the last
         * subscriber disappears and [stopTimeoutMillis] had passed,
         * after [replayExpirationMillis] more passed [resets then buffer][MutableSharedFlow.resetBuffer].
         *
         * This function throws [IllegalArgumentException] when either [stopTimeoutMillis] or [replayExpirationMillis]
         * are negative.
         *
         * @param stopTimeoutMillis time to wait (in milliseconds) before stopping sharing coroutine after
         *        the number of subscribers becomes zero. It defaults to zero (stop immediately).
         * @param replayExpirationMillis time to wait (in milliseconds) after stopping sharing before
         *        resetting the buffer. It defaults to `Long.MAX_VALUE` (keep replay cache forever, never reset buffer).
         */
        @Suppress("FunctionName")
        @ExperimentalCoroutinesApi
        public fun WhileSubscribed(stopTimeoutMillis: Long = 0, replayExpirationMillis: Long = Long.MAX_VALUE): SharingStarted =
            StartedWhileSubscribed(stopTimeoutMillis, replayExpirationMillis)
    }

    /**
     * Transforms the [subscriptionCount][MutableSharedFlow.subscriptionCount] state of the shared flow into the
     * flow of [commands][SharingCommand] that control sharing coroutine.
     */
    public fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand>
}

// -------------------------------- implementation --------------------------------

private class StartedEagerly : SharingStarted {
    private val alwaysStarted = unsafeDistinctFlow { emit(SharingCommand.START) }
    override fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = alwaysStarted
    override fun toString(): String = "SharingStarted.Eagerly"
}

private class StartedLazily : SharingStarted {
    override fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = unsafeDistinctFlow {
        var started = false
        subscriptionCount.collect { count ->
            if (count > 0 && !started) {
                started = true
                emit(SharingCommand.START)
            }
        }
    }

    override fun toString(): String = "SharingStarted.Lazily"
}

private class StartedWhileSubscribed(
    private val stopTimeout: Long,
    private val replayExpiration: Long
) : SharingStarted {
    init {
        require(stopTimeout >= 0) { "stopTimeout cannot be negative" }
        require(replayExpiration >= 0) { "replayExpiration cannot be negative" }
    }

    override fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = subscriptionCount
        .transformLatest { count ->
            if (count > 0) {
                emit(SharingCommand.START)
            } else {
                delay(stopTimeout)
                if (replayExpiration > 0) {
                    emit(SharingCommand.STOP)
                    delay(replayExpiration)
                }
                emit(SharingCommand.STOP_AND_RESET_BUFFER)
            }
        }
        .dropWhile { it != SharingCommand.START } // don't emit any STOP/RESET_BUFFER to start with, only START
        .distinctUntilChanged() // just in case somebody forgets it, don't leak our multiple sending of START

    @OptIn(ExperimentalStdlibApi::class)
    override fun toString(): String {
        val params = buildList(2) {
            if (stopTimeout > 0) add("stopTimeout=${stopTimeout}ms")
            if (replayExpiration < Long.MAX_VALUE) add("replayExpiration=${replayExpiration}ms")
        }
        return "SharingStarted.WhileSubscribed(${params.joinToString()})"
    }
}
