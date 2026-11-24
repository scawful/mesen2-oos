package ca.mesen.oos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class StatusWriterTest {
    @Test
    public void coalescesSnapshotsOntoSingleDaemonThreadWithoutBlockingCaller() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        List<String> written = Collections.synchronizedList(new ArrayList<>());
        Set<Thread> writerThreads = Collections.synchronizedSet(new HashSet<>());
        Thread caller = Thread.currentThread();

        MesenActivity.StatusWriter writer = new MesenActivity.StatusWriter(snapshot -> {
            writerThreads.add(Thread.currentThread());
            assertTrue("writer thread must be daemon", Thread.currentThread().isDaemon());
            assertNotEquals("writer must not run on caller thread", caller, Thread.currentThread());
            written.add(snapshot);
            if (written.size() == 1) {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            } else if (written.size() == 2) {
                secondDone.countDown();
            }
        });

        try {
            long t0 = System.nanoTime();
            writer.enqueueSnapshot("{\"seq\":1}");
            assertTrue("first write should begin promptly", firstEntered.await(5, TimeUnit.SECONDS));

            // Enqueue multiple updates while first write is blocked; caller must not block
            // and intermediate snapshots must coalesce to the latest one.
            writer.enqueueSnapshot("{\"seq\":2}");
            writer.enqueueSnapshot("{\"seq\":3}");
            writer.enqueueSnapshot("{\"seq\":4}");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue("enqueueSnapshot must not block on blocked writer", elapsedMs < 500);

            releaseFirst.countDown();
            assertTrue("coalesced latest write must complete", secondDone.await(5, TimeUnit.SECONDS));

            // Allow a brief settle to verify no extra stale intermediate writes occur.
            Thread.sleep(50);
            assertEquals(2, written.size());
            assertEquals("{\"seq\":1}", written.get(0));
            assertEquals("{\"seq\":4}", written.get(1));
            assertEquals("all writes must share one writer thread", 1, writerThreads.size());
        } finally {
            writer.shutdown();
        }
    }

    @Test
    public void shutdownStopsDaemonThreadAndIgnoresLaterEnqueues() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        MesenActivity.StatusWriter writer = new MesenActivity.StatusWriter(snapshot -> ran.countDown());
        writer.enqueueSnapshot("{\"ok\":true}");
        assertTrue(ran.await(5, TimeUnit.SECONDS));

        writer.shutdown();
        long deadline = System.currentTimeMillis() + 2000;
        while (writer.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse("daemon thread should exit on shutdown", writer.isAlive());
        writer.enqueueSnapshot("{\"after\":true}");
    }
}
