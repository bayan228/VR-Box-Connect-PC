package com.vrbox.client.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class VideoBufferTest {
    private fun fragment(id: Long, index: Int = 0, total: Int = 1, key: Boolean = true,
                         data: ByteArray = byteArrayOf(index.toByte()), timestamp: Long = 33) =
        Protocol.VideoFragment(id, index, total, key, timestamp, data)

    private fun unit(id: Long, key: Boolean = false, size: Int = 2) = AccessUnit(id, 33, key, ByteArray(size))

    @Test fun fragmentsReorderAndDuplicatesDoNotConsumeBudget() {
        val assembler = VideoReassembler()
        assertTrue(assembler.offer(fragment(1, 2, 3), 0).frames.isEmpty())
        assembler.offer(fragment(1, 0, 3), 1)
        assembler.offer(fragment(1, 0, 3), 2)
        assertEquals(2, assembler.bufferedBytes)
        val result = assembler.offer(fragment(1, 1, 3), 3)
        assertArrayEquals(byteArrayOf(0, 1, 2), result.frames.single().data)
        assertFalse(result.needsKeyframe)
        assertEquals(0, assembler.bufferedBytes)
        assertEquals(0, assembler.inFlightFrames)
        assertTrue(assembler.offer(fragment(1, 2, 3), 4).frames.isEmpty())
    }

    @Test fun wholeAccessUnitsReorderBehindAnIncompleteIdr() {
        val assembler = VideoReassembler()
        assembler.offer(fragment(11, key = false), 0)
        assembler.offer(fragment(10, 1, 2), 1)
        assembler.offer(fragment(12, key = false), 2)
        val result = assembler.offer(fragment(10, 0, 2), 3)
        assertEquals(listOf(10L, 11L, 12L), result.frames.map { it.frameId })
        assertFalse(result.needsKeyframe)
        assertEquals(0, assembler.bufferedBytes)
    }

    @Test fun newerIdrSkipsMissingPredecessorWithoutRemainingGated() {
        val assembler = VideoReassembler()
        assembler.offer(fragment(1), 0)
        assembler.offer(fragment(2, 0, 2, key = false), 1)
        val recovery = assembler.offer(fragment(3), 2)
        assertEquals(3L, recovery.frames.single().frameId)
        assertFalse(recovery.needsKeyframe)
        assertEquals(4L, assembler.offer(fragment(4, key = false), 3).frames.single().frameId)
    }

    @Test fun missingFragmentTimesOutAndGatesDependentPFrames() {
        val assembler = VideoReassembler()
        assembler.offer(fragment(1), 0)
        assembler.offer(fragment(2, 0, 2, key = false), 1)
        assertTrue(assembler.offer(fragment(3, key = false), 2).frames.isEmpty())
        assertFalse(assembler.poll(99_000_000).needsKeyframe)
        val expired = assembler.poll(100_000_001)
        assertTrue(expired.needsKeyframe)
        assertEquals(0, assembler.bufferedBytes)
        assertEquals(0, assembler.inFlightFrames)
        assertTrue(assembler.offer(fragment(2, 1, 2, key = false), 100_000_002).frames.isEmpty())
        assertTrue(assembler.offer(fragment(4, key = false), 100_000_003).frames.isEmpty())
        assertEquals(5L, assembler.offer(fragment(5), 100_000_004).frames.single().frameId)
        assertEquals(6L, assembler.offer(fragment(6, key = false), 100_000_005).frames.single().frameId)
    }

    @Test fun anEntireMissingAccessUnitAlsoTimesOut() {
        val assembler = VideoReassembler()
        assembler.offer(fragment(20), 0)
        assertTrue(assembler.offer(fragment(22, key = false), 1).frames.isEmpty())
        assertTrue(assembler.poll(100_000_001).needsKeyframe)
        assertTrue(assembler.offer(fragment(23, key = false), 100_000_002).frames.isEmpty())
    }

    @Test fun inconsistentMetadataAndConflictingDuplicatesInvalidateTheFrame() {
        val inconsistent = listOf(
            fragment(1, 1, 3), fragment(1, 1, 2, timestamp = 34),
            fragment(1, 1, 2, key = false), fragment(1, 0, 2, data = byteArrayOf(99))
        )
        for (bad in inconsistent) {
            val assembler = VideoReassembler()
            assembler.offer(fragment(1, 0, 2), 0)
            assertTrue(assembler.offer(bad, 1).needsKeyframe)
            assertEquals(0, assembler.bufferedBytes)
            assertTrue(assembler.offer(fragment(1, 1, 2), 2).frames.isEmpty())
            assertEquals(2L, assembler.offer(fragment(2), 3).frames.single().frameId)
        }
    }

    @Test fun atMostThreeAccessUnitsCanBeInFlight() {
        val assembler = VideoReassembler()
        for (id in 1L..3L) assembler.offer(fragment(id, 0, 2), 0)
        assertEquals(3, assembler.inFlightFrames)
        val overflow = assembler.offer(fragment(4, key = false), 1)
        assertTrue(overflow.needsKeyframe)
        assertTrue(overflow.frames.isEmpty())
        assertEquals(0, assembler.bufferedBytes)
        assertEquals(5L, assembler.offer(fragment(5), 2).frames.single().frameId)
    }

    @Test fun perAccessUnitAndAggregateByteBudgetsAreStrict() {
        val perFrame = VideoReassembler(maxAccessUnitBytes = 5, maxBufferedBytes = 20)
        perFrame.offer(fragment(1, 0, 2, data = ByteArray(3)), 0)
        assertTrue(perFrame.offer(fragment(1, 1, 2, data = ByteArray(3)), 1).needsKeyframe)
        assertEquals(0, perFrame.bufferedBytes)

        val aggregate = VideoReassembler(maxAccessUnitBytes = 10, maxBufferedBytes = 8)
        aggregate.offer(fragment(1, 0, 2, data = ByteArray(4)), 0)
        aggregate.offer(fragment(2, 0, 2, data = ByteArray(4)), 0)
        assertEquals(8, aggregate.bufferedBytes)
        assertTrue(aggregate.offer(fragment(3, 0, 2), 1).needsKeyframe)
        assertEquals(0, aggregate.bufferedBytes)
    }

    @Test fun exactlyTwoMiBIsAcceptedAndOneMoreFragmentByteIsRejected() {
        val maximum = Protocol.MAX_ACCESS_UNIT_SIZE
        val count = (maximum + Protocol.MAX_FRAGMENT_SIZE - 1) / Protocol.MAX_FRAGMENT_SIZE
        for (extra in 0..1) {
            val assembler = VideoReassembler()
            var remaining = maximum + extra
            var result = VideoReassembler.Result(emptyList(), true)
            for (index in 0 until count) {
                val size = minOf(remaining, Protocol.MAX_FRAGMENT_SIZE)
                result = assembler.offer(fragment(1, index, count, data = ByteArray(size) { 7 }), 0)
                remaining -= size
                assertTrue(assembler.bufferedBytes <= maximum)
            }
            if (extra == 0) assertEquals(maximum, result.frames.single().data.size)
            else {
                assertTrue(result.frames.isEmpty())
                assertTrue(result.needsKeyframe)
            }
            assertEquals(0, assembler.bufferedBytes)
        }
    }

    @Test fun invalidFragmentBoundsNeverAllocateUnboundedStorage() {
        for (bad in listOf(fragment(1, total = 2049), fragment(1, index = 1),
            fragment(1, data = ByteArray(1171)), fragment(1, data = byteArrayOf()),
            fragment(0x100000000L), fragment(1, timestamp = -1))) {
            val assembler = VideoReassembler()
            assertTrue(assembler.offer(bad, 0).needsKeyframe)
            assertEquals(0, assembler.inFlightFrames)
            assertEquals(0, assembler.bufferedBytes)
        }
    }

    @Test fun frameIdsWrapAndOldDuplicatesNeverReappear() {
        val assembler = VideoReassembler()
        assertEquals(0xffffffffL, assembler.offer(fragment(0xffffffffL), 0).frames.single().frameId)
        assertEquals(0L, assembler.offer(fragment(0, key = false), 1).frames.single().frameId)
        assertEquals(1L, assembler.offer(fragment(1, key = false), 2).frames.single().frameId)
        assertTrue(assembler.offer(fragment(0xffffffffL), 3).frames.isEmpty())
        assertTrue(assembler.offer(fragment(0, key = false), 4).frames.isEmpty())
    }

    @Test fun deterministicShufflesDuplicatesAndInputMutationAreSafe() {
        val random = Random(73)
        repeat(10) {
            val assembler = VideoReassembler()
            val order = ((0 until 30) + (0 until 30)).shuffled(random)
            val outputs = ArrayList<AccessUnit>()
            order.forEachIndexed { time, index ->
                val data = byteArrayOf(index.toByte())
                outputs += assembler.offer(fragment(1, index, 30, data = data), time.toLong()).frames
                data[0] = 99
            }
            assertEquals(1, outputs.size)
            assertArrayEquals(ByteArray(30) { it.toByte() }, outputs.single().data)
        }
    }

    @Test fun resetReleasesFragmentsAndStartsANewSessionAtAnyId() {
        val assembler = VideoReassembler()
        assembler.offer(fragment(50), 0)
        assembler.offer(fragment(51, 0, 2, key = false), 1)
        assembler.reset()
        assertEquals(0, assembler.bufferedBytes)
        assertEquals(0, assembler.inFlightFrames)
        assertEquals(0L, assembler.droppedFrames)
        assertTrue(assembler.waitingForKeyframe)
        assertEquals(1L, assembler.offer(fragment(1), 0).frames.single().frameId)
    }

    @Test fun decoderQueueStartsGatedAndOverflowDropsTheDependentChain() {
        val queue = DecodeQueue(maxFrames = 2, maxBytes = 8)
        assertFalse(queue.offer(unit(0)))
        assertTrue(queue.offer(unit(1, key = true, size = 3)))
        assertTrue(queue.offer(unit(2, size = 3)))
        assertFalse(queue.offer(unit(3, size = 3)))
        assertEquals(0, queue.size)
        assertEquals(0, queue.bufferedBytes)
        assertTrue(queue.waitingForKeyframe)
        assertFalse(queue.offer(unit(4)))
        assertTrue(queue.offer(unit(5, key = true)))
        assertEquals(5L, queue.poll()!!.frameId)
    }

    @Test fun decoderQueueByteLimitGapsAndExplicitInputDropsRequireIdr() {
        val queue = DecodeQueue(maxFrames = 3, maxBytes = 4)
        assertTrue(queue.offer(unit(1, key = true, size = 3)))
        assertFalse(queue.offer(unit(2, size = 2)))
        assertTrue(queue.offer(unit(3, key = true)))
        assertFalse(queue.offer(unit(5)))
        assertTrue(queue.offer(unit(6, key = true)))
        queue.invalidate()
        assertFalse(queue.offer(unit(7)))
        assertTrue(queue.offer(unit(8, key = true)))
    }

    @Test fun periodicIdrDoesNotClearAHealthyDecoderQueue() {
        val queue = DecodeQueue()
        assertTrue(queue.offer(unit(1, key = true)))
        assertTrue(queue.offer(unit(2)))
        assertTrue(queue.offer(unit(3, key = true)))
        assertEquals(listOf(1L, 2L, 3L), List(3) { queue.poll()!!.frameId })
        assertFalse(queue.waitingForKeyframe)
    }

    @Test fun decoderQueueCanRecoverAtOverflowUsingTheIncomingIdr() {
        val queue = DecodeQueue(maxFrames = 1)
        assertTrue(queue.offer(unit(1, key = true)))
        assertTrue(queue.offer(unit(2, key = true)))
        assertEquals(1, queue.size)
        assertEquals(2L, queue.poll()!!.frameId)
    }

    @Test fun closeIsIdempotentReleasesQueuedBytesAndWakesTheConsumer() {
        val queue = DecodeQueue()
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val consumer = Thread {
            entered.countDown()
            assertNull(queue.poll(5000))
            completed.countDown()
        }
        consumer.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        queue.close()
        queue.close()
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        consumer.join(1000)
        assertFalse(consumer.isAlive)
        assertFalse(queue.offer(unit(1, key = true)))
        assertEquals(0, queue.bufferedBytes)

        val nonempty = DecodeQueue()
        nonempty.offer(unit(1, key = true))
        nonempty.close()
        assertNull(nonempty.poll())
        assertEquals(0, nonempty.size)
    }
}
