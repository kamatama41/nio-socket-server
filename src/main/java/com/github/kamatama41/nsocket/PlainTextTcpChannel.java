package com.github.kamatama41.nsocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class PlainTextTcpChannel implements TcpChannel {
    private static final Logger log = LoggerFactory.getLogger(PlainTextTcpChannel.class);
    private final SocketChannel channel;
    private final IOProcessor.Loop belongingTo;
    private final Context context;
    private final CountDownLatch connectionTimer;
    private SocketAddress remoteSocketAddress;

    PlainTextTcpChannel(SocketChannel channel, IOProcessor.Loop belongingTo, Context context) {
        this.channel = channel;
        this.belongingTo = belongingTo;
        this.context = context;
        this.connectionTimer = new CountDownLatch(1);
    }

    @Override
    public void connect(SocketAddress remote, long timeoutSeconds, Connection connection) throws IOException {
        belongingTo.addEvent(() -> {
            log.trace("connect");
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            SelectionKey connectKey = channel.register(belongingTo.getSelector(), SelectionKey.OP_CONNECT);
            connectKey.attach(connection);
            channel.connect(remote);
        });
        try {
            if (!connectionTimer.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Connection timed out");
            }
        } catch (InterruptedException e) {
            throw new IOException("Connecting to server failed by interruption");
        }

        if (!channel.isOpen()) {
            throw new IOException(String.format("Failed to connect server (%s)", remote));
        }
    }

    @Override
    public void finishConnect(Connection connection) throws IOException {
        if (!channel.isConnectionPending()) {
            return;
        }
        channel.configureBlocking(false);

        if (!channel.finishConnect()) {
            throw new IOException("Failed to connect to server");
        }
        channel.socket().setTcpNoDelay(true);
        SelectionKey readKey = channel.register(belongingTo.getSelector(), SelectionKey.OP_READ);
        readKey.attach(connection);
        updateRemoteSocketAddress();

        // Notify connected
        connectionTimer.countDown();
    }

    @Override
    public void register(Connection connection) {
        belongingTo.addEvent(() -> {
            log.trace("register");
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            connection.assignConnectionId();

            final SelectionKey key = channel.register(belongingTo.getSelector(), SelectionKey.OP_READ);
            key.attach(connection);

            updateRemoteSocketAddress();
            connection.sendCommand(SetConnectionIdCommand.ID, connection.getConnectionId());

            context.getListenerRegistry().fireConnectedEvent(connection);
        });
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return channel.read(dst);
    }

    @Override
    public void write(ByteBuffer src) throws IOException {
        channel.write(src);
    }

    @Override
    public boolean isOpen() {
        return channel.isConnected() && channel.isOpen();
    }

    @Override
    public void close() throws IOException {
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
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return remoteSocketAddress;
    }

    @Override
    public void enableInterest(int ops) {
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

    @Override
    public void overrideInterest(int ops) {
        log.trace("overrideInterest: {}", ops);
        SelectionKey key = getKey();
        if (key != null && key.isValid()) {
            key.interestOps(ops);
        }
    }

    private void updateRemoteSocketAddress() {
        this.remoteSocketAddress = channel.socket().getRemoteSocketAddress();
    }

    private SelectionKey getKey() {
        return channel.keyFor(belongingTo.getSelector());
    }

    private static boolean alreadyIncluded(int current, int newOps) {
        return (current & newOps) == newOps;
    }
}