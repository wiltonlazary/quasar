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
package co.paralleluniverse.kotlin.fibers

import co.paralleluniverse.kotlin.*
import co.paralleluniverse.strands.Strand
import co.paralleluniverse.strands.channels.Channels

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/**
 * @author circlespainter
 */
class FiberKotlinTest {
  @Test fun testFiber() {
    assertTrue (
      fiber {
        println("Hi there")
        dumpStack()
        Strand.sleep(10)
        println("Hi there later")
        1
      }.get() == 1
    )
  }

  @Test fun testSelect() {
    val ch1 = Channels.newChannel<Int>(1)
    val ch2 = Channels.newChannel<Double>(1)

    assertTrue (
      fiber {
        select(Receive(ch1), Send(ch2, 2.0)) {
          dumpStack { it }
        }
      }.get() is Send<*>)

    ch1.send(1)

    assertTrue (
      fiber {
        select(Receive(ch1), Send(ch2, 2.0)) {
          when (it) {
            is Receive<*> -> dumpStack { it.msg }
            is Send<*> -> dumpStack { 0 }
            else -> dumpStack { -1 }
          }
        }
      }.get() == 1
    )

    assertTrue (
      fiber {
        select(10, TimeUnit.MILLISECONDS, Receive(ch1), Send(ch2, 2.0)) {
          when (it) {
            is Receive<*> -> dumpStack { it.msg }
            is Send<*> -> dumpStack { 0 }
            else -> dumpStack { -1 }
          }
        }
      }.get() == -1
    )
  }

  private fun <T> dumpStack(f : () -> T) : T {
    Throwable().printStackTrace()
    return f()
  }

  private fun dumpStack() : Unit {
    Throwable().printStackTrace()
  }
}
