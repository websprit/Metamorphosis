/*
 * (C) 2007-2012 Alibaba Group Holding Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Authors:
 *   wuhua <wq163@163.com> , boyan <killme2008@gmail.com>
 */
package com.taobao.metamorphosis.server.store;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.gecko.core.util.LinkedTransferQueue;
import com.taobao.metamorphosis.network.PutCommand;
import com.taobao.metamorphosis.server.utils.MetaConfig;
import com.taobao.metamorphosis.server.utils.SystemTimer;
import com.taobao.metamorphosis.server.utils.TopicConfig;
import com.taobao.metamorphosis.utils.CheckSum;
import com.taobao.metamorphosis.utils.MessageUtils;


/**
 * һ��topic����Ϣ�洢���ڲ��������ļ�(segment)
 * 
 * @author boyan
 * @Date 2011-4-20
 * @author wuhua
 * @Date 2011-6-26
 * 
 */
public class MessageStore extends Thread implements Closeable {
    private static final int ONE_M_BYTES = 512 * 1024;
    private static final String FILE_SUFFIX = ".meta";
    private volatile boolean closed = false;
    static final Log log = LogFactory.getLog(MessageStore.class);

    // ��ʾһ����Ϣ�ļ�
    static class Segment {
        // ��Ƭ�δ����offset
        final long start;
        // ��Ӧ���ļ�
        final File file;
        // ��Ƭ�ε���Ϣ����
        FileMessageSet fileMessageSet;


        public Segment(final long start, final File file) {
            this(start, file, true);
        }


        public Segment(final long start, final File file, final boolean mutable) {
            super();
            this.start = start;
            this.file = file;
            log.info("Created segment " + this.file.getAbsolutePath());
            try {
                final FileChannel channel = new RandomAccessFile(this.file, "rw").getChannel();
                this.fileMessageSet = new FileMessageSet(channel, 0, channel.size(), mutable);
                // // ���ɱ�ģ����ﲻ��ֱ����FileMessageSet(channel, false)
                // if (mutable == true) {
                // this.fileMessageSet.setMutable(true);
                // }
            }
            catch (final IOException e) {
                log.error("��ʼ����Ϣ����ʧ��", e);
            }
        }


        public long size() {
            return this.fileMessageSet.highWaterMark();
        }


        // �ж�offset�Ƿ��ڱ��ļ���
        public boolean contains(final long offset) {
            if (this.size() == 0 && offset == this.start || this.size() > 0 && offset >= this.start
                    && offset <= this.start + this.size() - 1) {
                return true;
            }
            else {
                return false;
            }
        }
    }

    /**
     * ���ɱ��segment list
     * 
     * @author boyan
     * @Date 2011-4-20
     * 
     */
    static class SegmentList {
        AtomicReference<Segment[]> contents = new AtomicReference<Segment[]>();


        public SegmentList(final Segment[] s) {
            this.contents.set(s);
        }


        public SegmentList() {
            super();
            this.contents.set(new Segment[0]);
        }


        public void append(final Segment segment) {
            while (true) {
                final Segment[] curr = this.contents.get();
                final Segment[] update = new Segment[curr.length + 1];
                System.arraycopy(curr, 0, update, 0, curr.length);
                update[curr.length] = segment;
                if (this.contents.compareAndSet(curr, update)) {
                    return;
                }
            }
        }


        public void delete(final Segment segment) {
            while (true) {
                final Segment[] curr = this.contents.get();
                int index = -1;
                for (int i = 0; i < curr.length; i++) {
                    if (curr[i] == segment) {
                        index = i;
                        break;
                    }

                }
                if (index == -1) {
                    return;
                }
                final Segment[] update = new Segment[curr.length - 1];
                // ����ǰ���
                System.arraycopy(curr, 0, update, 0, index);
                // ��������
                if (index + 1 < curr.length) {
                    System.arraycopy(curr, index + 1, update, index, curr.length - index - 1);
                }
                if (this.contents.compareAndSet(curr, update)) {
                    return;
                }
            }
        }


        public Segment[] view() {
            return this.contents.get();
        }


        public Segment last() {
            final Segment[] copy = this.view();
            if (copy.length > 0) {
                return copy[copy.length - 1];
            }
            return null;
        }


        public Segment first() {
            final Segment[] copy = this.view();
            if (copy.length > 0) {
                return copy[0];
            }
            return null;
        }

    }

    private SegmentList segments;
    private final File partitionDir;
    private final String topic;
    private final int partition;
    private final AtomicInteger unflushed;
    private final AtomicLong lastFlushTime;
    private final MetaConfig metaConfig;
    private final DeletePolicy deletePolicy;
    private final LinkedTransferQueue<WriteRequest> bufferQueue = new LinkedTransferQueue<WriteRequest>();
    private long maxTransferSize;
    int unflushThreshold = 1000;


    public MessageStore(final String topic, final int partition, final MetaConfig metaConfig,
            final DeletePolicy deletePolicy) throws IOException {
        this(topic, partition, metaConfig, deletePolicy, 0);
    }

    private volatile String desc;


    public String getDescription() {
        if (this.desc == null) {
            this.desc = this.topic + "-" + this.partition;
        }
        return this.desc;
    }


    public MessageStore(final String topic, final int partition, final MetaConfig metaConfig,
            final DeletePolicy deletePolicy, final long offsetIfCreate) throws IOException {
        this.metaConfig = metaConfig;
        this.topic = topic;
        final TopicConfig topicConfig = this.metaConfig.getTopicConfig(this.topic);
        String dataPath = metaConfig.getDataPath();
        if (topicConfig != null) {
            dataPath = topicConfig.getDataPath();
        }
        final File parentDir = new File(dataPath);
        this.checkDir(parentDir);
        this.partitionDir = new File(dataPath + File.separator + topic + "-" + partition);
        this.checkDir(this.partitionDir);
        // this.topic = topic;
        this.partition = partition;
        this.unflushed = new AtomicInteger(0);
        this.lastFlushTime = new AtomicLong(SystemTimer.currentTimeMillis());
        this.unflushThreshold = topicConfig.getUnflushThreshold();
        this.deletePolicy = deletePolicy;

        // Make a copy to avoid getting it again and again.
        this.maxTransferSize = metaConfig.getMaxTransferSize();
        this.maxTransferSize = this.maxTransferSize > ONE_M_BYTES ? ONE_M_BYTES : this.maxTransferSize;

        // Check directory and load exists segments.
        this.checkDir(this.partitionDir);
        this.loadSegments(offsetIfCreate);
        if (this.useGroupCommit()) {
            this.start();
        }
    }


    public long getMessageCount() {
        long sum = 0;
        for (final Segment seg : this.segments.view()) {
            sum += seg.fileMessageSet.getMessageCount();
        }
        return sum;
    }


    public long getSizeInBytes() {
        long sum = 0;
        for (final Segment seg : this.segments.view()) {
            sum += seg.fileMessageSet.getSizeInBytes();
        }
        return sum;
    }


    SegmentList getSegments() {
        return this.segments;
    }


    File getPartitionDir() {
        return this.partitionDir;
    }


    @Override
    public void close() throws IOException {
        this.closed = true;
        this.interrupt();
        try {
            this.join(500);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (final Segment segment : this.segments.view()) {
            segment.fileMessageSet.close();
        }
    }


    public void runDeletePolicy() {
        if (this.deletePolicy == null) {
            return;
        }
        final long start = System.currentTimeMillis();
        final Segment[] view = this.segments.view();
        for (final Segment segment : view) {
            // �ǿɱ䲢�ҿ�ɾ��
            if (!segment.fileMessageSet.isMutable() && this.deletePolicy.canDelete(segment.file, start)) {
                log.info("Deleting file " + segment.file.getAbsolutePath() + " with policy " + this.deletePolicy.name());
                this.segments.delete(segment);
                try {
                    segment.fileMessageSet.close();
                    this.deletePolicy.process(segment.file);
                }
                catch (final IOException e) {
                    log.error("�رղ�ɾ��file message setʧ��", e);
                }

            }
        }

    }


    /**
     * ���ز�У���ļ�
     */
    private void loadSegments(final long offsetIfCreate) throws IOException {
        final List<Segment> accum = new ArrayList<Segment>();
        final File[] ls = this.partitionDir.listFiles();

        if (ls != null) {
            for (final File file : ls) {
                if (file.isFile() && file.toString().endsWith(FILE_SUFFIX)) {
                    if (!file.canRead()) {
                        throw new IOException("Could not read file " + file);
                    }
                    final String filename = file.getName();
                    final long start = Long.parseLong(filename.substring(0, filename.length() - FILE_SUFFIX.length()));
                    // ����Ϊ���ɱ�ļ��ؽ���
                    accum.add(new Segment(start, file, false));
                }
            }
        }

        if (accum.size() == 0) {
            // û�п��õ��ļ�������һ����������offsetIfCreate��ʼ
            final File newFile = new File(this.partitionDir, this.nameFromOffset(offsetIfCreate));
            accum.add(new Segment(offsetIfCreate, newFile));
        }
        else {
            // ������һ���ļ���У�鲢����start��������
            Collections.sort(accum, new Comparator<Segment>() {
                @Override
                public int compare(final Segment o1, final Segment o2) {
                    if (o1.start == o2.start) {
                        return 0;
                    }
                    else if (o1.start > o2.start) {
                        return 1;
                    }
                    else {
                        return -1;
                    }
                }
            });
            // У���ļ�
            this.validateSegments(accum);
            // ���һ���ļ��޸�Ϊ�ɱ�
            final Segment last = accum.remove(accum.size() - 1);
            last.fileMessageSet.close();
            log.info("Loading the last segment in mutable mode and running recover on " + last.file.getAbsolutePath());
            final Segment mutable = new Segment(last.start, last.file);
            accum.add(mutable);
            log.info("Loaded " + accum.size() + " segments...");
        }

        this.segments = new SegmentList(accum.toArray(new Segment[accum.size()]));
    }


    private void validateSegments(final List<Segment> segments) {
        this.writeLock.lock();
        try {
            for (int i = 0; i < segments.size() - 1; i++) {
                final Segment curr = segments.get(i);
                final Segment next = segments.get(i + 1);
                if (curr.start + curr.size() != next.start) {
                    throw new IllegalStateException("The following segments don't validate: "
                            + curr.file.getAbsolutePath() + ", " + next.file.getAbsolutePath());
                }
            }
        }
        finally {
            this.writeLock.unlock();
        }
    }


    private void checkDir(final File dir) {
        if (!dir.exists()) {
            if (!dir.mkdir()) {
                throw new RuntimeException("Create directory failed:" + dir.getAbsolutePath());
            }
        }
        if (!dir.isDirectory()) {
            throw new RuntimeException("Path is not a directory:" + dir.getAbsolutePath());
        }
    }

    private final ReentrantLock writeLock = new ReentrantLock();


    /**
     * Append������Ϣ������д���λ��
     * 
     * @param msgId
     * @param req
     * @return
     */
    public void append(final long msgId, final PutCommand req, final AppendCallback cb) {
        this.appendBuffer(MessageUtils.makeMessageBuffer(msgId, req), cb);
    }

    private static class WriteRequest {
        public final ByteBuffer buf;
        public final AppendCallback cb;
        public Location result;


        public WriteRequest(final ByteBuffer buf, final AppendCallback cb) {
            super();
            this.buf = buf;
            this.cb = cb;
        }
    }


    private void appendBuffer(final ByteBuffer buffer, final AppendCallback cb) {
        if (this.closed) {
            throw new IllegalStateException("Closed MessageStore.");
        }
        if (this.useGroupCommit() && buffer.remaining() < this.maxTransferSize) {
            this.bufferQueue.offer(new WriteRequest(buffer, cb));
        }
        else {
            Location location = null;
            final int remainning = buffer.remaining();
            this.writeLock.lock();
            try {
                final Segment cur = this.segments.last();
                final long offset = cur.start + cur.fileMessageSet.append(buffer);
                this.mayBeFlush(1);
                this.mayBeRoll();
                location = Location.create(offset, remainning);
            }
            catch (final IOException e) {
                log.error("Append file failed", e);
                location = Location.InvalidLocaltion;
            }
            finally {
                this.writeLock.unlock();
                if (cb != null) {
                    cb.appendComplete(location);
                }
            }
        }
    }


    private void notifyCallback(AppendCallback callback, Location location) {
        try {
            callback.appendComplete(location);
        }
        catch (Exception e) {
            log.error("Call AppendCallback failed", e);
        }
    }


    private boolean useGroupCommit() {
        return this.unflushThreshold <= 0;
    }


    @Override
    public void run() {
        // �ȴ�force�Ķ���
        final LinkedList<WriteRequest> toFlush = new LinkedList<WriteRequest>();
        WriteRequest req = null;
        long lastFlushPos = 0;
        Segment last = null;
        while (!this.closed && !Thread.currentThread().isInterrupted()) {
            try {
                if (last == null) {
                    last = this.segments.last();
                    lastFlushPos = last.fileMessageSet.highWaterMark();
                }
                if (req == null) {
                    if (toFlush.isEmpty()) {
                        req = this.bufferQueue.take();
                    }
                    else {
                        req = this.bufferQueue.poll();
                        if (req == null || last.fileMessageSet.getSizeInBytes() > lastFlushPos + this.maxTransferSize) {
                            // ǿ��force
                            last.fileMessageSet.flush();
                            lastFlushPos = last.fileMessageSet.highWaterMark();
                            // ֪ͨ�ص�
                            for (final WriteRequest request : toFlush) {
                                request.cb.appendComplete(request.result);
                            }
                            toFlush.clear();
                            // �Ƿ���Ҫroll
                            this.mayBeRoll();
                            // ����л��ļ������»�ȡlast
                            if (this.segments.last() != last) {
                                last = null;
                            }
                            continue;
                        }
                    }
                }

                if (req == null) {
                    continue;
                }
                final int remainning = req.buf.remaining();
                final long offset = last.start + last.fileMessageSet.append(req.buf);
                req.result = Location.create(offset, remainning);
                if (req.cb != null) {
                    toFlush.add(req);
                }
                req = null;
            }
            catch (final IOException e) {
                log.error("Append message failed,*critical error*,the group commit thread would be terminated.", e);
                // TODO io�쳣û�취�����ˣ�������?
                break;
            }
            catch (final InterruptedException e) {
                // ignore
            }
        }
        // terminated
        try {
            for (WriteRequest request : this.bufferQueue) {
                final int remainning = request.buf.remaining();
                final long offset = last.start + last.fileMessageSet.append(request.buf);
                if (request.cb != null) {
                    request.cb.appendComplete(Location.create(offset, remainning));
                }
            }
            this.bufferQueue.clear();
        }
        catch (IOException e) {
            log.error("Append message failed", e);
        }

    }


    /**
     * Append�����Ϣ������д���λ��
     * 
     * @param msgIds
     * @param reqs
     * 
     * @return
     */
    public void append(final List<Long> msgIds, final List<PutCommand> putCmds, final AppendCallback cb) {
        this.appendBuffer(MessageUtils.makeMessageBuffer(msgIds, putCmds), cb);
    }


    /**
     * �ط���������������Ϣû�д洢�ɹ��������´洢���������µ�λ��
     * 
     * @param to
     * @param msgIds
     * @param reqs
     * @return
     * @throws IOException
     */
    public void replayAppend(final long offset, final int length, final int checksum, final List<Long> msgIds,
            final List<PutCommand> reqs, final AppendCallback cb) throws IOException {
        final Segment segment = this.findSegment(this.segments.view(), offset);
        if (segment == null) {
            this.append(msgIds, reqs, cb);
        }
        else {
            final MessageSet messageSet =
                    segment.fileMessageSet.slice(offset - segment.start, offset - segment.start + length);
            final ByteBuffer buf = ByteBuffer.allocate(length);
            messageSet.read(buf, offset - segment.start);
            buf.flip();
            final byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            // ���У�����������Ϣ��У��ͣ����message��У��Ͳ�һ����ע������
            final int checkSumInDisk = CheckSum.crc32(bytes);
            // û�д��룬�����´洢
            if (checksum != checkSumInDisk) {
                this.append(msgIds, reqs, cb);
            }
            else {
                // �����洢����Ϣ�����账��
                if (cb != null) {
                    this.notifyCallback(cb, null);
                }
            }
        }
    }


    public String getTopic() {
        return this.topic;
    }


    public int getPartition() {
        return this.partition;
    }


    private void mayBeRoll() throws IOException {
        if (this.segments.last().fileMessageSet.getSizeInBytes() >= this.metaConfig.getMaxSegmentSize()) {
            this.roll();
        }
    }


    String nameFromOffset(final long offset) {
        final NumberFormat nf = NumberFormat.getInstance();
        nf.setMinimumIntegerDigits(20);
        nf.setMaximumFractionDigits(0);
        nf.setGroupingUsed(false);
        return nf.format(offset) + FILE_SUFFIX;
    }


    private void roll() throws IOException {
        final long newOffset = this.nextAppendOffset();
        final File newFile = new File(this.partitionDir, this.nameFromOffset(newOffset));
        this.segments.last().fileMessageSet.flush();
        this.segments.last().fileMessageSet.setMutable(false);
        this.segments.append(new Segment(newOffset, newFile));
    }


    private long nextAppendOffset() throws IOException {
        final Segment last = this.segments.last();
        last.fileMessageSet.flush();
        return last.start + last.size();
    }


    private void mayBeFlush(final int numOfMessages) throws IOException {
        if (this.unflushed.addAndGet(numOfMessages) > this.metaConfig.getTopicConfig(this.topic).getUnflushThreshold()
                || SystemTimer.currentTimeMillis() - this.lastFlushTime.get() > this.metaConfig.getTopicConfig(
                    this.topic).getUnflushInterval()) {
            this.flush0();
        }
    }


    public List<SegmentInfo> getSegmentInfos() {
        final List<SegmentInfo> rt = new ArrayList<SegmentInfo>();
        for (final Segment seg : this.segments.view()) {
            rt.add(new SegmentInfo(seg.start, seg.size()));
        }
        return rt;
    }


    public void flush() throws IOException {
        this.writeLock.lock();
        try {
            this.flush0();
        }
        finally {
            this.writeLock.unlock();
        }
    }


    private void flush0() throws IOException {
        if (this.useGroupCommit()) {
            return;
        }
        this.segments.last().fileMessageSet.flush();
        this.unflushed.set(0);
        this.lastFlushTime.set(SystemTimer.currentTimeMillis());
    }


    /**
     * ���ص�ǰ���ɶ���offset
     * 
     * @return
     */
    public long getMaxOffset() {
        final Segment last = this.segments.last();
        return last.start + last.size();
    }


    /**
     * ���ص�ǰ��С�ɶ���offset
     * 
     * @return
     */
    public long getMinOffset() {
        return this.segments.first().start;
    }


    /**
     * ����offset��maxSize��������MessageSet, ��offset�������offset��ʱ�򷵻�null��
     * ��offsetС����Сoffset��ʱ���׳�ArrayIndexOutOfBounds�쳣
     * 
     * @param offset
     * 
     * @param maxSize
     * @return
     * @throws IOException
     */
    public MessageSet slice(final long offset, final int maxSize) throws IOException {
        final Segment segment = this.findSegment(this.segments.view(), offset);
        if (segment == null) {
            return null;
        }
        else {
            return segment.fileMessageSet.slice(offset - segment.start, offset - segment.start + maxSize);
        }
    }


    /**
     * ������ָ��offset��ǰ׷������Ŀ���offset ,�������offset������Χ��ʱ�򷵻ر߽�offset
     * 
     * @param offset
     * @return
     */
    public long getNearestOffset(final long offset) {
        return this.getNearestOffset(offset, this.segments);
    }


    long getNearestOffset(final long offset, final SegmentList segments) {
        try {
            final Segment segment = this.findSegment(segments.view(), offset);
            if (segment != null) {
                return segment.start;
            }
            else {
                final Segment last = segments.last();
                return last.start + last.size();
            }
        }
        catch (final ArrayIndexOutOfBoundsException e) {
            return segments.first().start;
        }
    }


    /**
     * ����offset�����ļ�,�������β�����򷵻�null�������ͷ��֮ǰ�����׳�ArrayIndexOutOfBoundsException
     * 
     * @param segments
     * @param offset
     * @return �����ҵ�segment���������β�����򷵻�null�������ͷ��֮ǰ�����׳��쳣
     * @throws ArrayIndexOutOfBoundsException
     */
    Segment findSegment(final Segment[] segments, final long offset) {
        if (segments == null || segments.length < 1) {
            return null;
        }
        // �ϵ����ݲ����ڣ�����������ϵ�����
        final Segment last = segments[segments.length - 1];
        // ��ͷ����ǰ���׳��쳣
        if (offset < segments[0].start) {
            throw new ArrayIndexOutOfBoundsException();
        }
        // �պ���β�����߳�����Χ������null
        if (offset >= last.start + last.size()) {
            return null;
        }
        // ����offset���ֲ���
        int low = 0;
        int high = segments.length - 1;
        while (low <= high) {
            final int mid = high + low >>> 1;
        final Segment found = segments[mid];
        if (found.contains(offset)) {
            return found;
        }
        else if (offset < found.start) {
            high = mid - 1;
        }
        else {
            low = mid + 1;
        }
        }
        return null;
    }
}