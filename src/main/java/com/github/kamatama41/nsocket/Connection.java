package com.github.kamatama41.nsocket;

import com.github.kamatama41.nsocket.codec.ObjectCodec;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessageInsufficientBufferException;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Connection {
    private static final int DEFAULT_CONTENT_SIZE = 8 * 1024;
    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    protected final SocketChannel channel;
    protected final IOProcessor.Loop belongingTo;
    private final Context context;
    private final ObjectCodec codec;
    private final SyncManager syncManager;
    private final CommandRegistry commandRegistry;
    protected final CommandListenerRegistry listenerRegistry;
    private final CommandWorker worker;
    private Queue<ByteBuffer> writeQueue;
    private ByteBuffer contentBuffer;
    private Object attachment;
    private long lastHeartbeatTime;
    private boolean isClosed;

    Connection(
            SocketChannel channel, IOProcessor.Loop belongingTo, CommandWorker worker, Context context) {
        this.channel = channel;
        this.belongingTo = belongingTo;
        this.worker = worker;
        this.context = context;
        this.codec = context.getCodec();
        this.syncManager = context.getSyncManager();
        this.commandRegistry = context.getCommandRegistry();
        this.listenerRegistry = context.getListenerRegistry();
        this.writeQueue = new ConcurrentLinkedQueue<>();
        this.contentBuffer = ByteBuffer.allocate(DEFAULT_CONTENT_SIZE);
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.isClosed = false;
    }

    public void sendCommand(String id, Object body) {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packString(codec.encodeToJson(new CommandData(id, null, body)));
            write(ByteBuffer.wrap(packer.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T, R> R sendSyncCommand(String id, T body) {
        SyncCommand syncCommand = commandRegistry.getSyncCommand(id);
        SyncManager.Request request = syncManager.registerNewRequest();
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packString(codec.encodeToJson(new CommandData(id, request.getCallId(), body)));
            write(ByteBuffer.wrap(packer.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            long timeoutMillis = syncCommand.getTimeoutMillis() + 100L; // Add a buffer of networking
            boolean completed = request.waitUntilCompleted(timeoutMillis);
            if (!completed) {
                throw new SyncCommandException("A sync command could not return response");
            }
        } catch (InterruptedException e) {
            throw new SyncCommandException("A sync command is interrupted");
        }

        SyncResultData result = request.getResult();
        if (result.getStatus() == SyncResultData.Status.FAILED) {
            throw new SyncCommandException("A sync command failed: " + result.getErrorMessage());
        }

        if (result.getStatus() == SyncResultData.Status.TIMEOUT) {
            throw new SyncCommandException("A sync command was timed out");
        }
        return (R) result.getResult();
    }

    public boolean isOpen() {
        return channel.isConnected() && channel.isOpen();
    }

    public void attach(Object attachment) {
        this.attachment = attachment;
    }

    public Object attachment() {
        return attachment;
    }

    public synchronized void close() throws IOException {
        log.trace("Closing connection.");
        if (isClosed) {
            log.warn("Connection already closed");
        }
        if (channel.isOpen()) {
            Selector selector = belongingTo.getSelector();
            SelectionKey key = getKey();
            selector.wakeup();
            if (key != null) {
                key.cancel();
                key.attach(null);
            }
            channel.close();
        }
        isClosed = true;
        listenerRegistry.fireDisconnectedEvent(this);
    }

    void onConnectable() throws IOException {
        throw new UnsupportedOperationException("onConnectable");
    }

    void onWritable() throws IOException {
        if (writeQueue.isEmpty()) {
            overrideInterest(SelectionKey.OP_READ);
            return;
        }

        ByteBuffer buffer;
        while ((buffer = writeQueue.peek()) != null) {
            channel.write(buffer);
            if (buffer.remaining() != 0) {
                return;
            } else {
                writeQueue.poll();
            }
        }
        overrideInterest(SelectionKey.OP_READ);
    }

    void onReadable() throws IOException {
        if (!isOpen()) {
            close();
            return;
        }

        int read;
        ByteBuffer content = contentBuffer;
        do {
            read = channel.read(content);
        } while (content.hasRemaining() && read > 0);

        if (read == -1) {
            close();
            return;
        }

        content.flip();
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(content)) {
            while (unpacker.hasNext()) {
                worker.addRequest(new CommandRequest(unpacker.unpackString(), this));
                content.position((int) unpacker.getTotalReadBytes());
            }
            content.clear();
        } catch (MessageInsufficientBufferException e) {
            content.compact();
            if (!content.hasRemaining()) {
                log.warn("Message size larger than buffer's size ({}), will expand it.", content.capacity());
                content.flip();
                expandContentBufferSize();
            }
        }
    }

    void sendHeartbeat() throws IOException {
        long timeout = context.getHeartbeatInterval() * 3;
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatTime >= timeout) {
            log.warn("Connection might be dead.");
            close();
        }
        sendCommand(HeartbeatCommand.ID, null);
    }

    void updateLastHeartbeatTime() {
        lastHeartbeatTime = System.currentTimeMillis();
        log.trace("updateLastHeartbeatTime: {}", lastHeartbeatTime);
    }

    private void expandContentBufferSize() {
        ByteBuffer newBuffer = ByteBuffer.allocate(contentBuffer.capacity() * 2);
        newBuffer.put(contentBuffer);
        contentBuffer = newBuffer;
    }

    private void enableInterest(int ops) {
        belongingTo.addEvent(() -> {
            log.trace("enableInterest: {}", ops);
            SelectionKey key = getKey();
            if (key != null && key.isValid()) {
                int current = key.interestOps();
                if (!alreadyIncluded(current, ops)) {
                    int newOps = key.interestOps() | ops;
                    key.interestOps(newOps);
                    log.trace("Updated to {}", ops);
                }
            }
        });
    }

    private void overrideInterest(int ops) {
        log.trace("overrideInterest: {}", ops);
        SelectionKey key = getKey();
        if (key != null && key.isValid()) {
            key.interestOps(ops);
        }
    }

    private void write(ByteBuffer data) {
        if (isOpen()) {
            writeQueue.add(data);
            enableInterest(SelectionKey.OP_WRITE);
        }
    }

    private SelectionKey getKey() {
        return channel.keyFor(belongingTo.getSelector());
    }

    private static boolean alreadyIncluded(int current, int newOps) {
        return (current & newOps) == newOps;
    }
}
