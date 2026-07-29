/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR  CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.core.ipc.sink.offheap;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.opennms.core.ipc.sink.api.DispatchQueue;
import org.opennms.core.ipc.sink.api.DispatchQueue.EnqueueResult;
import org.opennms.core.ipc.sink.api.QueueCreateFailedException;
import org.opennms.core.ipc.sink.api.WriteFailedException;

/**
 * Regression test for a deadlock between a reader and an in-flight flush in
 * {@link DataBlocksOffHeapQueue}.
 *
 * When a block filled up, enqueue() called flushToDisk(), which submits a worker that must
 * acquire diskLock to serialize the batch and publish its result into the future. A reader
 * arriving mid-flush ran enableQueue(), which took diskLock with tryLock() and only then waited
 * for that future. tryLock() deliberately ignores the lock's fairness, so the reader could barge
 * ahead of the queued worker: it then waited forever for a future the worker could no longer
 * complete, while holding the very lock the worker needed.
 *
 * The visible symptom was a consumer wedged in Object.wait() inside dequeue() while the producer
 * kept enqueuing, so a queue stopped draining at a block boundary and never recovered. It was
 * timing dependent and so appeared as an intermittent test hang on some machines and not others.
 *
 * The queue backs the IPC sink that Minion and Sentinel use to buffer messages while a broker is
 * unavailable, so a permanently stalled consumer is a production concern, not only a test one.
 *
 * A fast producer is what makes this reproduce: it keeps memory full, so blocks are flushed to
 * disk continually and the reader keeps meeting flushes in progress. Against the unfixed queue
 * this times out; with the fix it completes in well under a second.
 */
public class DataBlocksOffHeapQueueStallTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test(timeout = 60_000)
    public void drainsWhileBlocksAreBeingFlushedToDisk() throws IOException, QueueCreateFailedException, InterruptedException {
        final int batchSize = 5;
        final int inMemoryQueueSize = 20;
        final int numEntries = 11_111;

        DispatchQueue<String> queue = new DataBlocksOffHeapQueue<>(String::getBytes, String::new,
                "drainsWhileBlocksAreBeingFlushedToDisk", Paths.get(folder.newFolder().toURI()),
                inMemoryQueueSize, batchSize, 100_000_000);

        List<String> dequeued = new ArrayList<>();
        AtomicInteger deferrals = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Fast producer, so memory fills and blocks get flushed to disk -- the state in
            // which enqueue() returns DEFERRED. Retry rather than drop.
            executor.execute(() -> {
                for (int i = 0; i < numEntries; i++) {
                    try {
                        String value = Integer.toString(i);
                        // enqueue() returns DEFERRED without storing the message when the tail
                        // block has just been flushed to disk. A producer that ignores the
                        // result silently drops it, which is what the two pre-existing
                        // canQueueAndDequeueInParallel tests do.
                        while (queue.enqueue(value, value) == EnqueueResult.DEFERRED) {
                            deferrals.incrementAndGet();
                            Thread.sleep(1);
                        }
                    } catch (WriteFailedException e) {
                        throw new RuntimeException(e);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });

            for (int i = 0; i < numEntries; i++) {
                dequeued.add(queue.dequeue().getValue());
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        System.out.println("DEFERRED results observed: " + deferrals.get());
        assertEquals(numEntries, dequeued.size());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < numEntries; i++) {
            expected.add(Integer.toString(i));
        }
        assertEquals(expected, dequeued);
    }
}
