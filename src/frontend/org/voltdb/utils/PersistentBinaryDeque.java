/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.DBBPool;
import org.voltcore.utils.DBBPool.BBContainer;
import org.voltcore.utils.DBBPool.MBBContainer;
import org.voltcore.utils.DeferredSerialization;
import org.voltdb.EELibraryLoader;
import org.voltdb.utils.BinaryDeque.TruncatorResponse.Status;
import org.xerial.snappy.Snappy;

import com.google_voltpatches.common.base.Joiner;
import com.google_voltpatches.common.base.Throwables;

/**
 * A deque that specializes in providing persistence of binary objects to disk. Any object placed
 * in the deque will be persisted to disk asynchronously. Objects placed in the deque can
 * be persisted synchronously by invoking sync. The files backing this deque all start with a nonce
 * provided at construction time followed by a segment index that is stored in the filename. Files grow to
 * a maximum size of 64 megabytes and then a new segment is created. The index starts at 0. Segments are deleted
 * once all objects from the segment have been polled and all the containers returned by poll have been discarded.
 * Push is implemented by creating new segments at the head of the deque containing the objects to be pushed.
 *
 */
public class PersistentBinaryDeque implements BinaryDeque {
    private static final VoltLogger LOG = new VoltLogger("HOST");

    public static class UnsafeOutputContainerFactory implements OutputContainerFactory {
        @Override
        public BBContainer getContainer(int minimumSize) {
              final BBContainer origin = DBBPool.allocateUnsafeByteBuffer(minimumSize);
              final BBContainer retcont = new BBContainer(origin.b()) {
                  private boolean discarded = false;

                  @Override
                  public synchronized void discard() {
                      final ByteBuffer buf = checkDoubleFree();
                      if (discarded) {
                          LOG.error("Avoided double discard in PBD");
                          return;
                      }
                      discarded = true;
                      origin.discard();
                  }
              };
              return retcont;
        }
    }

    public static final OutputContainerFactory UNSAFE_CONTAINER_FACTORY = new UnsafeOutputContainerFactory();

    /**
     * Processors also log using this facility.
     */
    private final VoltLogger m_usageSpecificLog;

    private final File m_path;
    private final String m_nonce;
    private boolean m_initializedFromExistingFiles = false;

    //Segments that are no longer being written to and can be polled
    //These segments are "immutable". They will not be modified until deletion
    private final Deque<PBDSegment> m_segments = new ArrayDeque<PBDSegment>();
    private int m_numObjects = 0;
    private volatile boolean m_closed = false;

    /**
     * Create a persistent binary deque with the specified nonce and storage
     * back at the specified path. Existing files will
     *
     * @param nonce
     * @param path
     * @param logger
     * @throws IOException
     */
    public PersistentBinaryDeque(final String nonce, final File path, VoltLogger logger) throws IOException {
        this(nonce, path, logger, true);
    }

    /**
     * Create a persistent binary deque with the specified nonce and storage back at the specified path.
     * This is convenient method for test so that
     * poll with delete can be tested.
     *
     * @param nonce
     * @param path
     * @param deleteEmpty
     * @throws IOException
     */
    public PersistentBinaryDeque(final String nonce, final File path, VoltLogger logger, final boolean deleteEmpty) throws IOException {
        EELibraryLoader.loadExecutionEngineLibrary(true);
        m_path = path;
        m_nonce = nonce;
        m_usageSpecificLog = logger;

        if (!path.exists() || !path.canRead() || !path.canWrite() || !path.canExecute() || !path.isDirectory()) {
            throw new IOException(path + " is not usable ( !exists || !readable " +
                    "|| !writable || !executable || !directory)");
        }

        final TreeMap<Long, PBDSegment> segments = new TreeMap<Long, PBDSegment>();
        //Parse the files in the directory by name to find files
        //that are part of this deque
        try {
            path.listFiles(new FileFilter() {

                @Override
                public boolean accept(File pathname) {
                    // PBD file names have three parts: nonce.seq.pbd
                    // nonce may contain '.', seq is a sequence number.
                    String[] parts = pathname.getName().split("\\.");
                    String parsedNonce = null;
                    String seqNum = null;
                    String extension = null;

                    // If more than 3 parts, it means nonce contains '.', assemble them.
                    if (parts.length > 3) {
                        Joiner joiner = Joiner.on('.').skipNulls();
                        parsedNonce = joiner.join(Arrays.asList(parts).subList(0, parts.length - 2));
                        seqNum = parts[parts.length - 2];
                        extension = parts[parts.length - 1];
                    } else if (parts.length == 3) {
                        parsedNonce = parts[0];
                        seqNum = parts[1];
                        extension = parts[2];
                    }

                    if (nonce.equals(parsedNonce) && "pbd".equals(extension)) {
                        if (pathname.length() == 4) {
                            //Doesn't have any objects, just the object count
                            pathname.delete();
                            return false;
                        }
                        Long index = Long.valueOf(seqNum);
                        PBDSegment qs = newSegment( index, pathname );
                        try {
                            qs.open(false);
                            m_initializedFromExistingFiles = true;
                            if (deleteEmpty) {
                                if (qs.getNumEntries() == 0) {
                                    LOG.info("Found Empty Segment with entries: " + qs.getNumEntries() + " For: " + pathname.getName());
                                    qs.closeAndDelete();
                                    return false;
                                }
                            }
                            m_numObjects += qs.getNumEntries();
                            qs.close();
                            segments.put( index, qs);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return false;
                }

            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw new IOException(e);
            }
            Throwables.propagate(e);
        }

        Long lastKey = null;
        for (Map.Entry<Long, PBDSegment> e : segments.entrySet()) {
            final Long key = e.getKey();
            if (lastKey == null) {
                lastKey = key;
            } else {
                if (lastKey + 1 != key) {
                    try {
                        for (PBDSegment pbds : segments.values()) {
                            pbds.close();
                        }
                    } catch (Exception ex) {}
                    throw new IOException("Missing " + nonce +
                            " pbd segments between " + lastKey + " and " + key + " in directory " + path +
                            ". The data files found in the export overflow directory were inconsistent.");
                }
                lastKey = key;
            }
            m_segments.offer(e.getValue());
        }

        //Find the first and last segment for polling and writing (after)
        Long writeSegmentIndex = 0L;
        try {
            writeSegmentIndex = segments.lastKey() + 1;
        } catch (NoSuchElementException e) {}

        PBDSegment writeSegment =
            newSegment(
                    writeSegmentIndex,
                    new VoltFile(m_path, m_nonce + "." + writeSegmentIndex + ".pbd"));
        m_segments.offer(writeSegment);
        writeSegment.open(true);
        assertions();
    }

    private static final boolean USE_MMAP = Boolean.getBoolean("PBD_USE_MMAP");
    private PBDSegment newSegment(long segmentId, File file) {
        if (USE_MMAP) {
            return new PBDMMapSegment(segmentId, file);
        } else {
            return new PBDRegularSegment(segmentId, file);
        }
    }

    /**
     * Close the tail segment if it's not being read from currently, then offer the new segment.
     * @throws IOException
     */
    private void closeTailAndOffer(PBDSegment newSegment) throws IOException {
        final PBDSegment last = m_segments.peekLast();
        if (last != null && !last.isBeingPolled()) {
            last.close();
        }
        m_segments.offer(newSegment);
    }

    @Override
    public synchronized void offer(BBContainer object) throws IOException {
        offer(object, true);
    }

    @Override
    public synchronized void offer(BBContainer object, boolean allowCompression) throws IOException {
        assertions();
        if (m_closed) {
            throw new IOException("Closed");
        }

        PBDSegment tail = m_segments.peekLast();
        final boolean compress = object.b().isDirect() && allowCompression;
        if (!tail.offer(object, compress)) {
            tail = addSegment(tail);
            final boolean success = tail.offer(object, compress);
            if (!success) {
                throw new IOException("Failed to offer object in PBD");
            }
        }
        incrementNumObjects();
        assertions();
    }

    @Override
    public synchronized int offer(DeferredSerialization ds) throws IOException {
        assertions();
        if (m_closed) {
            throw new IOException("Closed");
        }

        PBDSegment tail = m_segments.peekLast();
        int written = tail.offer(ds);
        if (written < 0) {
            tail = addSegment(tail);
            written = tail.offer(ds);
            if (written < 0) {
                throw new IOException("Failed to offer object in PBD");
            }
        }
        incrementNumObjects();
        assertions();
        return written;
    }

    private PBDSegment addSegment(PBDSegment tail) throws IOException {
        //Check to see if the tail is completely consumed so we can close and delete it
        if (!tail.hasMoreEntries() && tail.isEmpty()) {
            m_segments.pollLast();
            tail.closeAndDelete();
        }
        Long nextIndex = tail.segmentId() + 1;
        tail = newSegment(nextIndex, new VoltFile(m_path, m_nonce + "." + nextIndex + ".pbd"));
        tail.open(true);
        closeTailAndOffer(tail);
        return tail;
    }

    @Override
    public synchronized void push(BBContainer objects[]) throws IOException {
        assertions();
        if (m_closed) {
            throw new IOException("Closed");
        }

        ArrayDeque<ArrayDeque<BBContainer>> segments = new ArrayDeque<ArrayDeque<BBContainer>>();
        ArrayDeque<BBContainer> currentSegment = new ArrayDeque<BBContainer>();

        //Take the objects that were provided and separate them into deques of objects
        //that will fit in a single write segment
        int available = PBDSegment.CHUNK_SIZE - 4;
        for (BBContainer object : objects) {
            int needed = PBDSegment.OBJECT_HEADER_BYTES + object.b().remaining();

            if (available - needed < 0) {
                if (needed > PBDSegment.CHUNK_SIZE - 4) {
                    throw new IOException("Maximum object size is " + (PBDSegment.CHUNK_SIZE - 4));
                }
                segments.offer( currentSegment );
                currentSegment = new ArrayDeque<BBContainer>();
                available = PBDSegment.CHUNK_SIZE - 4;
            }
            available -= needed;
            currentSegment.add(object);
        }

        segments.add(currentSegment);
        assert(segments.size() > 0);

        //Calculate the index for the first segment to push at the front
        //This will be the index before the first segment available for read or
        //before the write segment if there are no finished segments
        Long nextIndex = 0L;
        if (m_segments.size() > 0) {
            nextIndex = m_segments.peek().segmentId() - 1;
        }

        while (segments.peek() != null) {
            ArrayDeque<BBContainer> currentSegmentContents = segments.poll();
            PBDSegment writeSegment =
                newSegment(
                        nextIndex,
                        new VoltFile(m_path, m_nonce + "." + nextIndex + ".pbd"));
            writeSegment.open(true);
            nextIndex--;

            while (currentSegmentContents.peek() != null) {
                writeSegment.offer(currentSegmentContents.pollFirst(), false);
                incrementNumObjects();
            }

            // Don't close the last one, it'll be used for writes
            if (!m_segments.isEmpty()) {
                writeSegment.close();
            }

            m_segments.push(writeSegment);
        }
        assertions();
    }

    @Override
    public synchronized BBContainer poll(OutputContainerFactory ocf) throws IOException {
        assertions();
        if (m_closed) {
            throw new IOException("Closed");
        }

        BBContainer retcont = null;
        PBDSegment segment = null;

        for (PBDSegment s : m_segments) {
            if (s.isClosed()) {
                s.open(false);
            }

            if (s.hasMoreEntries()) {
                segment = s;
                retcont = segment.poll(ocf);
                break;
            }
        }

        if (retcont == null) {
            return null;
        }

        decrementNumObjects();
        assertions();
        assert (retcont.b() != null);
        return wrapRetCont(segment, retcont);
    }

    private BBContainer wrapRetCont(final PBDSegment segment, final BBContainer retcont) {
        return new BBContainer(retcont.b()) {
            @Override
            public void discard() {
                checkDoubleFree();
                retcont.discard();
                assert(m_closed || m_segments.contains(segment));

                //Don't do anything else if we are closed
                if (m_closed) {
                    return;
                }

                //Segment is potentially ready for deletion
                try {
                    if (segment.isEmpty()) {
                        if (segment != m_segments.peekLast()) {
                            m_segments.remove(segment);
                            segment.closeAndDelete();
                        }
                    }
                } catch (IOException e) {
                    LOG.error("Exception closing and deleting PBD segment", e);
                }
            }
        };
    }

    @Override
    public synchronized void sync() throws IOException {
        if (m_closed) {
            throw new IOException("Closed");
        }
        for (PBDSegment segment : m_segments) {
            if (!segment.isClosed()) {
                segment.sync();
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (m_closed) {
            return;
        }
        m_closed = true;
        for (PBDSegment segment : m_segments) {
            segment.close();
        }
        m_closed = true;
    }

    @Override
    public synchronized boolean isEmpty() throws IOException {
        assertions();
        if (m_closed) {
            throw new IOException("Closed");
        }

        for (PBDSegment s : m_segments) {
            final boolean wasClosed = s.isClosed();
            try {
                if (wasClosed) s.open(false);
                if (s.hasMoreEntries()) return false;
            } finally {
                if (wasClosed) {
                    s.close();
                }
            }
        }
        return true;
    }

    /*
     * Don't use size in bytes to determine empty, could potentially
     * diverge from object count on crash or power failure
     * although incredibly unlikely
     */
    @Override
    public long sizeInBytes() throws IOException {
        assertions();
        long size = 0;
        for (PBDSegment segment : m_segments) {
            final boolean wasClosed = segment.isClosed();
            if (wasClosed) segment.open(false);
            size += segment.uncompressedBytesToRead();
            if (wasClosed) {
                segment.close();
            }
        }
        return size;
    }

    @Override
    public synchronized void closeAndDelete() throws IOException {
        if (m_closed) return;
        m_closed = true;
        for (PBDSegment qs : m_segments) {
            qs.closeAndDelete();
        }
    }

    public static class ByteBufferTruncatorResponse extends TruncatorResponse {
        private final ByteBuffer m_retval;

        public ByteBufferTruncatorResponse(ByteBuffer retval) {
            super(Status.PARTIAL_TRUNCATE);
            assert retval.remaining() > 0;
            m_retval = retval;
        }

        @Override
        public int writeTruncatedObject(ByteBuffer output) {
            int objectSize = m_retval.remaining();
            output.putInt(objectSize);
            output.putInt(PBDSegment.NO_FLAGS);
            output.put(m_retval);
            return objectSize;
        }
    }

    public static class DeferredSerializationTruncatorResponse extends TruncatorResponse {
        public static interface Callback {
            public void bytesWritten(int bytes);
        }

        private final DeferredSerialization m_ds;
        private final Callback m_truncationCallback;

        public DeferredSerializationTruncatorResponse(DeferredSerialization ds, Callback truncationCallback) {
            super(Status.PARTIAL_TRUNCATE);
            m_ds = ds;
            m_truncationCallback = truncationCallback;
        }

        @Override
        public int writeTruncatedObject(ByteBuffer output) throws IOException {
            int bytesWritten = PBDUtils.writeDeferredSerialization(output, m_ds);
            if (m_truncationCallback != null) {
                m_truncationCallback.bytesWritten(bytesWritten);
            }
            return bytesWritten;
        }
    }

    public static TruncatorResponse fullTruncateResponse() {
        return new TruncatorResponse(Status.FULL_TRUNCATE);
    }

    @Override
    public synchronized void parseAndTruncate(BinaryDequeTruncator truncator) throws IOException {
        assertions();
        if (m_segments.isEmpty()) {
            m_usageSpecificLog.debug("PBD " + m_nonce + " has no finished segments");
            return;
        }

        /*
         * Iterator all the objects in all the segments and pass them to the truncator
         * When it finds the truncation point
         */
        Long lastSegmentIndex = null;
        BBContainer decompressionBuffer = DBBPool.allocateDirect(1024 * 512);
        try {
            for (PBDSegment segment : m_segments) {
                long segmentIndex = segment.segmentId();

                File segmentFile = segment.file();
                RandomAccessFile ras = new RandomAccessFile(segmentFile, "rw");
                FileChannel fc = ras.getChannel();
                MBBContainer readBufferC = DBBPool.wrapMBB(fc.map(MapMode.READ_WRITE, 0, fc.size()));
                final ByteBuffer readBuffer = readBufferC.b();
                final long buffAddr = readBufferC.address();
                try {
                    //Get the number of objects and then iterator over them
                    int numObjects = readBuffer.getInt();
                    readBuffer.position(PBDSegment.SIZE_OFFSET + 4);
                    int sizeInBytes = 0;

                    int objectsProcessed = 0;
                    m_usageSpecificLog.debug("PBD " + m_nonce + " has " + numObjects + " objects to parse and truncate");
                    for (int ii = 0; ii < numObjects; ii++) {
                        final int nextObjectLength = readBuffer.getInt();
                        final int nextObjectFlags = readBuffer.getInt();
                        final boolean compressed = (nextObjectFlags & PBDSegment.FLAG_COMPRESSED) != 0;
                        final int uncompressedLength = compressed ? (int)Snappy.uncompressedLength(buffAddr + readBuffer.position(), nextObjectLength) : nextObjectLength;
                        objectsProcessed++;
                        //Copy the next object into a separate heap byte buffer
                        //do the old limit stashing trick to avoid buffer overflow
                        BBContainer nextObject = null;
                        if (compressed) {
                            decompressionBuffer.b().clear();
                            if (decompressionBuffer.b().remaining() < uncompressedLength ) {
                                decompressionBuffer.discard();
                                decompressionBuffer = DBBPool.allocateDirect(uncompressedLength);
                            }
                            nextObject = DBBPool.dummyWrapBB(decompressionBuffer.b());
                            final long sourceAddr = (buffAddr + readBuffer.position());
                            final long destAddr = nextObject.address();
                            Snappy.rawUncompress(sourceAddr, nextObjectLength, destAddr);
                            readBuffer.position(readBuffer.position() + nextObjectLength);
                        } else {
                            final int oldLimit = readBuffer.limit();
                            readBuffer.limit(readBuffer.position() + nextObjectLength);
                            nextObject = DBBPool.dummyWrapBB(readBuffer.slice());
                            readBuffer.position(readBuffer.limit());
                            readBuffer.limit(oldLimit);
                        }
                        try {
                            //Handoff the object to the truncator and await a decision
                            TruncatorResponse retval = truncator.parse(nextObject);
                            if (retval == null) {
                                //Nothing to do, leave the object alone and move to the next
                                sizeInBytes += uncompressedLength;
                                continue;
                            } else {
                                //If the returned bytebuffer is empty, remove the object and truncate the file
                                if (retval.status == Status.FULL_TRUNCATE) {
                                    if (ii == 0) {
                                        /*
                                         * If truncation is occuring at the first object
                                         * Whammo! Delete the file. Do it by setting the lastSegmentIndex
                                         * to 1 previous. We may end up with an empty finished segment
                                         * set.
                                         */
                                        lastSegmentIndex = segmentIndex - 1;
                                    } else {
                                        addToNumObjects(-(numObjects - (objectsProcessed - 1)));
                                        //Don't forget to update the number of entries in the file
                                        ByteBuffer numObjectsBuffer = ByteBuffer.allocate(8);
                                        numObjectsBuffer.putInt(ii);
                                        numObjectsBuffer.putInt(sizeInBytes);
                                        numObjectsBuffer.flip();
                                        fc.position(0);
                                        while (numObjectsBuffer.hasRemaining()) {
                                            fc.write(numObjectsBuffer);
                                        }
                                        fc.truncate(readBuffer.position() - (nextObjectLength + PBDSegment.OBJECT_HEADER_BYTES));
                                    }
                                } else {
                                    assert retval.status == Status.PARTIAL_TRUNCATE;
                                    addToNumObjects(-(numObjects - objectsProcessed));
                                    //Partial object truncation
                                    readBuffer.position(readBuffer.position() - (nextObjectLength + PBDSegment.OBJECT_HEADER_BYTES));
                                    sizeInBytes += retval.writeTruncatedObject(readBuffer);
                                    readBuffer.putInt(PBDSegment.COUNT_OFFSET, ii + 1);
                                    readBuffer.putInt(PBDSegment.SIZE_OFFSET, sizeInBytes);
                                    /*
                                     * SHOULD REALLY make a copy of the original and then swap them with renaming
                                     */
                                    fc.truncate(readBuffer.position());
                                }
                                //Set last segment and break the loop over this segment
                                if (lastSegmentIndex == null) {
                                    lastSegmentIndex = segmentIndex;
                                }
                                break;
                            }
                        } finally {
                            nextObject.discard();
                        }
                    }

                    //If this is set the just processed segment was the last one
                    if (lastSegmentIndex != null) {
                        break;
                    }
                } finally {
                    fc.close();
                    readBufferC.discard();
                }
            }
        } finally {
            decompressionBuffer.discard();
        }

        /*
         * If it was found that no truncation is necessary, lastSegmentIndex will be null.
         * Return and the parseAndTruncate is a noop.
         */
        if (lastSegmentIndex == null)  {
            return;
        }
        /*
         * Now truncate all the segments after the truncation point
         */
        Iterator<PBDSegment> iterator = m_segments.descendingIterator();
        while (iterator.hasNext()) {
            PBDSegment segment = iterator.next();
            if (segment.segmentId() <= lastSegmentIndex) {
                break;
            }
            addToNumObjects(-segment.getNumEntries());
            iterator.remove();
            segment.closeAndDelete();
        }

        /*
         * Reset the poll and write segments
         */
        //Find the first and last segment for polling and writing (after)
        Long newSegmentIndex = 0L;
        if (m_segments.peekLast() != null) newSegmentIndex = m_segments.peekLast().segmentId() + 1;

        PBDSegment newSegment =
            newSegment(
                    newSegmentIndex,
                    new VoltFile(m_path, m_nonce + "." + newSegmentIndex + ".pbd"));
        newSegment.open(true);
        m_segments.offer(newSegment);
        assertions();
    }

    private void addToNumObjects(int num) {
        assert(m_numObjects >= 0);
        m_numObjects += num;
    }
    private void incrementNumObjects() {
        assert(m_numObjects >= 0);
         m_numObjects++;
    }

    private void decrementNumObjects() {
        m_numObjects--;
        assert(m_numObjects >= 0);
    }

    @Override
    public int getNumObjects() {
        return m_numObjects;
    }

    @Override
    public boolean initializedFromExistingFiles() {
        return m_initializedFromExistingFiles;
    }

    private static final boolean assertionsOn;
    static {
        boolean assertOn = false;
        assert(assertOn = true);
        assertionsOn = assertOn;
    }

    private void assertions() {
        if (!assertionsOn || m_closed) return;
        int numObjects = 0;
        for (PBDSegment segment : m_segments) {
            final boolean wasClosed = segment.isClosed();
            try {
                numObjects += segment.getNumEntries() - segment.readIndex();
            } catch (Exception e) {
                Throwables.propagate(e);
            }
            if (wasClosed) {
                try {
                    segment.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        assert numObjects == m_numObjects : numObjects + " != " + m_numObjects;
    }
}
