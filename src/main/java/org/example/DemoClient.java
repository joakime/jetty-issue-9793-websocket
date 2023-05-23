package org.example;

import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Future;

public class DemoClient implements Runnable {

    public static final int MESSAGE_COUNT = 10000;
    public static final int MIN_MESSAGE_LENGTH = 4;
    public static final int MAX_MESSAGE_LENGTH = 1024 * 4;

    /**
     * Connects to the server and sends {@link #MESSAGE_COUNT} random generated random length messages.
     * Also checks whether the server answers with the same messages.
     */
    public static void test() {
        new Thread(new DemoClient()).run();
    }

    @Override
    public void run() {
        WebSocketClient client = new WebSocketClient();
        LifeCycle.start(client);

        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        Future<Session> connect = null;
        try {
            ClientSocket socket = new ClientSocket();
            connect = client.connect(socket, DemoServer.SERVER_URI, clientUpgradeRequest);
            // the http response from the server
            UpgradeResponse resp = connect.get().getUpgradeResponse();
            //System.out.println(resp);

            ArrayList<ByteBuffer> buffers = generate(MESSAGE_COUNT, MAX_MESSAGE_LENGTH);
            System.out.println("Generated " + buffers.size() + " buffers.");
            for ( int i = 0; i < buffers.size(); i++ )
                socket.send(buffers.get(i));
        }
        catch ( Exception e ) {
            throw new RuntimeException(e);
        }
    }

    @Nonnull
    private static ArrayList<ByteBuffer> generate(int count, int capacity) {
        ArrayList<ByteBuffer> buffers = new ArrayList<>(count);
        for ( int i = 0; i < count; i++ ) {
            ByteBuffer buffer = generate((int) (Math.random() * capacity));
            buffers.add(buffer);
        }
        return buffers;
    }

    @Nonnull
    private static ByteBuffer generate(int capacity) {
        if ( capacity < MIN_MESSAGE_LENGTH )
            capacity = MIN_MESSAGE_LENGTH;

        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.position(0);
        buffer.putInt(capacity);
        for ( int i = 4; i < capacity; i++ )
            buffer.put((byte) (i % 256));
        return buffer.flip();
    }

    /**
     * Returns {@code True} if the {@code buffer} contains the expected amount of data.
     */
    synchronized static boolean check(@Nonnull String message, @Nonnull ByteBuffer buffer) {
        //System.out.println(message + buffer);
        boolean result = true;
        int position = buffer.position();
        buffer.position(0);
        int capacity = buffer.getInt();
        if ( capacity != buffer.limit() ) {
            System.out.println(message + buffer);
            System.out.println("Expected " + capacity + " received " + buffer.limit());
            System.out.println("\n");
            result = false;
        }
        buffer.position(position);
        //System.out.println("\n");
        return result;
    }

    @WebSocket
    public static class ClientSocket {
        private static final Logger LOG = LoggerFactory.getLogger(ClientSocket.class);
        private Session session;
        private ByteBufferAccumulator byteBufferAccumulator = new ByteBufferAccumulator();

        @OnWebSocketConnect
        public void onWebSocketConnect(Session session) {
            this.session = session;

            session.setInputBufferSize(DemoServer.BUFFER_SIZE);
            session.setOutputBufferSize(DemoServer.BUFFER_SIZE);
        }

        @OnWebSocketFrame
        public void onWebSocketFrame(@Nonnull Session session, @Nonnull Frame frame) {
            if (LOG.isDebugEnabled())
                LOG.debug("onWebSocketFrame(): {}", frame);
            Frame.Type webSocketFrameType = frame.getType();
            // BROKEN TEST: does not check if CONTINUATION belongs to a previous a BINARY (fin=false) (and not a TEXT) frame.
            if (frame.hasPayload() && webSocketFrameType == Frame.Type.CONTINUATION || webSocketFrameType == Frame.Type.BINARY) {
                ByteBuffer buffer = frame.getPayload();
                byteBufferAccumulator.copyBuffer(buffer);
                if (frame.isFin()) {
                    ByteBuffer completeMessage = byteBufferAccumulator.takeByteBuffer();
                    try {
                        check("Client receiving ", completeMessage);
                    } finally {
                        byteBufferAccumulator.getByteBufferPool().release(completeMessage);
                    }
                }
            }
        }

        public void send(@Nonnull ByteBuffer payload) {
            try {
                //System.out.println("Client sending " + payload);
                this.session.getRemote().sendBytes(payload);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
