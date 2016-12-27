/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2015-2016, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.kotlin

import co.paralleluniverse.actors.KotlinActorSupport
import java.util.concurrent.TimeUnit
import co.paralleluniverse.actors.LifecycleMessage
import co.paralleluniverse.actors.Actor as JActor
import co.paralleluniverse.fibers.Suspendable
import co.paralleluniverse.actors.ActorRef
import co.paralleluniverse.actors.ExitMessage
import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.strands.SuspendableCallable
import co.paralleluniverse.strands.queues.QueueIterator

/**
 * Ported from {@link co.paralleluniverse.actors.SelectiveReceiveHelper}
 *
 * @author circlespainter
 */
abstract class Actor : KotlinActorSupport<Any?, Any?>() {
    companion object {
        object DeferException : Exception()
        object Timeout
    }

    protected var currentMessage: Any? = null

    /**
     * Higher-order selective receive
     */
    inline protected fun receive(proc: (Any) -> Any?) {
        receive(-1, null, proc)
    }

    /**
     * Let the actor handle lifecycle messages
     */
    override fun handleLifecycleMessage(lcm: LifecycleMessage): Any? {
        return lcm
    }

    /**
     * Higher-order selective receive
     */
    inline protected fun receive(timeout: Long, unit: TimeUnit?, proc: (Any) -> Any?) {
        assert(JActor.currentActor<Any?, Any?>() == null || JActor.currentActor<Any?, Any?>() == this)

        val mailbox = mailbox()

        checkThrownIn1()
        mailbox.maybeSetCurrentStrandAsOwner()

        val start = if (timeout > 0) System.nanoTime() else 0
        var now: Long
        var left = if (unit != null) unit.toNanos(timeout) else 0
        val deadline = start + left

        monitorResetSkippedMessages()
        var i: Int = 0
        val it: QueueIterator<Any> = mailboxQueue().iterator()
        while (true) {
            if (flightRecorder != null)
                record(1, "KotlinActor", "receive", "%s waiting for a message. %s", this, if (timeout > 0) "millis left: " + TimeUnit.MILLISECONDS.convert(left, TimeUnit.NANOSECONDS) else "")

            mailbox.lock()

            if (it.hasNext()) {
                val m = it.next()
                mailbox.unlock()
                if (m == currentMessage) {
                    it.remove()
                    continue
                }

                record(1, "KotlinActor", "receive", "Received %s <- %s", this, m)
                monitorAddMessage()
                if (m is ExitMessage && m.getWatch() == null) {
                    // Delay all lifecycle messages except link death signals
                    it.remove()
                    handleLifecycleMessage(m)
                } else {
                    currentMessage = m
                    try {
                        val res = proc(m)

                        if (res != null) {
                            checkAndRemove(it, m)
                            return
                        } else // Discard
                            checkAndRemove(it, m)

                    } catch (d: DeferException) {
                        // Leave it there and go on to the next one
                        record(1, "KotlinActor", "receive", "%s skipped %s", this, m)
                        monitorSkippedMessage()
                    } catch (e: Exception) {
                        checkAndRemove(it, m)
                        throw e
                    } finally {
                        currentMessage = null
                    }
                }
            } else {
                try {
                    if (unit == null)
                        mailbox.await(i)
                    else if (timeout > 0) {
                        mailbox.await(i, left, TimeUnit.NANOSECONDS)

                        now = System.nanoTime()
                        left = deadline - now
                        if (left <= 0) {
                            record(1, "KotlinActor", "receive", "%s timed out.", this)
                            proc(Timeout)
                        }
                    }
                } finally {
                    mailbox.unlock()
                }
            }
        }
    }

    protected fun checkAndRemove(it: QueueIterator<Any>, m: Any) {
        if (it.value() == m) // Checking it's still there before removing it because another call to receive from within the processor may have removed it already
            it.remove()
    }

    protected fun defer() {
        throw DeferException;
    }
}

// A couple of top-level utils

fun spawn(a: JActor<*, *>): ActorRef<Any?> {
    @Suppress("UNCHECKED_CAST")
    Fiber(a as SuspendableCallable<Any>).start()
    @Suppress("UNCHECKED_CAST")
    return a.ref() as ActorRef<Any?>
}

fun register(ref: String, v: JActor<*, *>): JActor<*, *> {
    return v.register(ref)
}
