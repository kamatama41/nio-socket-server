package com.github.kamatama41.nsocket;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

interface TcpChannel {
    static TcpChannel open(SocketChannel channel, IOProcessor.Loop belongingTo, Context context) {
        if (context.getSslContext().isEnabled()) {
            return new SslTcpChannel(channel, belongingTo, context);
        }
        return new PlaintextTcpChannel(channel, belongingTo);
    }

    void connect(SocketAddress remote, long timeoutSeconds, Connection connection) throws IOException;

    void finishConnect(Connection connection) throws IOException;

    void register(Connection connection);

    int read(ByteBuffer dst) throws IOException;

    int write(ByteBuffer src) throws IOException;

    boolean isOpen();

    void close() throws IOException;

    SocketAddress getRemoteSocketAddress();

    void enableInterest(int ops);

    void overrideInterest(int ops);
}
