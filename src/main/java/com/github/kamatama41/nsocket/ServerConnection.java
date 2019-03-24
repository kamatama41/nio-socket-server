package com.github.kamatama41.nsocket;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

class ServerConnection extends Connection {
    ServerConnection(
            SocketChannel channel,
            IOProcessor.Loop belongingTo,
            CommandWorker worker,
            Context context) {
        super(channel, belongingTo, worker, context);
    }

    void register() {
        belongingTo.addEvent(() -> {
            log.trace("register");
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            final SelectionKey key = channel.register(belongingTo.getSelector(), SelectionKey.OP_READ);
            key.attach(this);
            listenerRegistry.fireConnectedEvent(this);
        });
    }
}
