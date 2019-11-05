/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.impl;

import com.google.common.base.Throwables;
import org.iq80.leveldb.Logger;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.env.DbLock;
import org.iq80.leveldb.env.Env;
import org.iq80.leveldb.env.File;
import org.iq80.leveldb.env.RandomInputFile;
import org.iq80.leveldb.env.SequentialFile;
import org.iq80.leveldb.env.WritableFile;
import org.iq80.leveldb.memenv.MemEnv;
import org.iq80.leveldb.util.Slice;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class OutOfSpaceTest
{
    public static final String OUT_OF_SPACE = "Out of space";

    private interface Disk
    {
        void write(int contentSize) throws IOException;
    }

    @DataProvider(name = "diskSize")
    private Object[][] diskSizeProvider()
    {
        return new Object[][] {
            new Object[] {2 << 8},
            new Object[] {2 << 10},
            new Object[] {2 << 12},
            new Object[] {2 << 16},
            new Object[] {2 << 24},
        };
    }

    /**
     * Write concurrently to db and fail at some point like an out of memory.
     * Verify that all theads fail not for invalid state but due to Out of memory exception.
     * It is expected that DB should not be usable after a write failure (in write request or compaction).
     */
    @Test(invocationCount = 20, dataProvider = "diskSize")
    public void testAllWritesShouldFailAfterFirstWriteFailure(int diskSpace) throws Exception
    {
        AtomicInteger disk = new AtomicInteger(0);
        Disk check = contentSize -> {
            if (disk.addAndGet(contentSize) > diskSpace) {
                throw new IOException(OUT_OF_SPACE);
            }
        };
        final Options rawOptions = new Options();

        final DbImpl db = new DbImpl(rawOptions, "leveldb", new MyEnv(check));
        //simulate concurrent work with random batch side, this will stimulate multi write grouping into one batch
        //inside the DB
        final int threads = 4;
        final ExecutorService executorService = Executors.newFixedThreadPool(threads);
        try {
            Future<Exception>[] fut = new Future[threads];
            for (int i = 0; i < threads; i++) {
                //at some point all thread should fail due to out of space exception
                fut[i] = executorService.submit(() -> {
                    final Random rand = new Random(Thread.currentThread().getId());
                    try {
                        while (true) {
                            writeRadomBatch(db, rand);
                        }
                    }
                    catch (Exception e) {
                        return e;
                    }
                });
            }
            //wait for all thread
            //all threads should fail because of continuous write.
            for (Future<Exception> exceptionFuture : fut) {
                final Exception exception = exceptionFuture.get(1, TimeUnit.MINUTES);
                final Throwable rootCause = Throwables.getRootCause(exception);
                assertNotNull(rootCause, "Route cause is expected in thrown exception" + exception);
                exception.printStackTrace();
                assertTrue(rootCause.getMessage().equals("Out of space"), "Out of space exception is expected as route cause of failure in " + exception);
            }

            //DB should be failed with background failure, so any new write should fail with background exception cause
            //last check to verify that if we try to write additional records to DB we get same route cause twice
            final Assert.ThrowingRunnable shouldFail = () -> {
                try (WriteBatchImpl updates = new WriteBatchImpl()) {
                    updates.put(new byte[] {1, 2, 3, 5}, new byte[] {45, 5, 6, 7});
                    db.write(updates);
                    Assert.fail("expected to fail");
                }
            };
            Throwable t1 = Throwables.getRootCause(Assert.expectThrows(Exception.class, shouldFail));
            Throwable t2 = Throwables.getRootCause(Assert.expectThrows(Exception.class, shouldFail));
            assertSame(t1, t2);
            assertNotNull(t1, "Route cause is expected in thrown exception" + t1);
            assertTrue(t1.getMessage().equals(OUT_OF_SPACE), "Out of space exception is expected as route cause of failure in " + t1);
        }
        finally {
            executorService.shutdown();
        }
    }

    private void writeRadomBatch(DbImpl db, Random rand)
    {
        try (WriteBatchImpl updates = new WriteBatchImpl()) {
            final int batchSize = rand.nextInt(10) + 1;
            for (int j = 0; j < batchSize; j++) {
                final int keySize = rand.nextInt(300) + 15;
                final int valueSize = rand.nextInt(1000) + 10;
                final byte[] kByte = new byte[keySize];
                final byte[] vByte = new byte[valueSize];
                rand.nextBytes(kByte);
                rand.nextBytes(vByte);
                if (rand.nextInt(20) % 20 == 0) {
                    updates.delete(kByte);
                }
                else {
                    updates.put(kByte, vByte);
                }
            }
            db.write(updates);
        }
    }

    private static class WrapperWritableFile implements WritableFile
    {
        private final WritableFile writableFile;
        private final Disk check;

        public WrapperWritableFile(WritableFile writableFile, Disk check)
        {
            this.writableFile = writableFile;
            this.check = check;
        }

        @Override
        public void append(Slice data) throws IOException
        {
            check.write(data.length());
            writableFile.append(data);
        }

        @Override
        public void force() throws IOException
        {
            check.write(100); //simulate some write
            writableFile.force();
        }

        @Override
        public void close() throws IOException
        {
            check.write(100); //simulate some write
            writableFile.close();
        }
    }

    private static class MyEnv implements Env
    {
        final Env env;
        private final Disk check;

        public MyEnv(Disk check)
        {
            this.check = check;
            this.env = MemEnv.createEnv();
        }

        @Override
        public long nowMicros()
        {
            return env.nowMicros();
        }

        @Override
        public File toFile(String filename)
        {
            return env.toFile(filename);
        }

        @Override
        public File createTempDir(String prefix)
        {
            return env.createTempDir(prefix);
        }

        @Override
        public SequentialFile newSequentialFile(File file) throws IOException
        {
            return env.newSequentialFile(file);
        }

        @Override
        public RandomInputFile newRandomAccessFile(File file) throws IOException
        {
            return env.newRandomAccessFile(file);
        }

        @Override
        public WritableFile newWritableFile(File file) throws IOException
        {
            final WritableFile writableFile = env.newWritableFile(file);
            return new WrapperWritableFile(writableFile, check);
        }

        @Override
        public WritableFile newAppendableFile(File file) throws IOException
        {
            return new WrapperWritableFile(env.newAppendableFile(file), check);
        }

        @Override
        public void writeStringToFileSync(File file, String content) throws IOException
        {
            check.write(content.length());
            env.writeStringToFileSync(file, content);
        }

        @Override
        public String readFileToString(File file) throws IOException
        {
            return env.readFileToString(file);
        }

        @Override
        public Logger newLogger(File loggerFile) throws IOException
        {
            return env.newLogger(loggerFile);
        }

        @Override
        public DbLock tryLock(File lockFile) throws IOException
        {
            return env.tryLock(lockFile);
        }
    }
}
