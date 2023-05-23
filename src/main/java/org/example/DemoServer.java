package org.example;

import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.server.*;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;

public class DemoServer {

    public static final String SERVER_HOST = "localhost";
    public static final int SERVER_PORT = 20000;
    public static final String SERVER_PATH = "/messaging";

    public static URI SERVER_URI;

    public static final int BUFFER_SIZE = 1024 * 1024 * 2;

    /**
     * Starts the webserver, waits for websocket messages from the {@link DemoClient#test() client} then exists.
     * The webserver sends back the {@link DemoClient#check(String, ByteBuffer) correct} messages to the client.
     */
    public static void main(String... args) throws Exception {
        long heapMaxSize = Runtime.getRuntime().maxMemory();
        System.out.println("Heap max size: " + heapMaxSize);

        DemoServer server = new DemoServer();
        server.start();
        SERVER_URI = WSURI.toWebsocket(server.getURI());

        DemoClient.test();

        System.exit(0);
    }

    final Server server;

    DemoServer() {
        this.server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(SERVER_PORT);
        connector.setHost(SERVER_HOST);
        server.addConnector(connector);
    }

    URI getURI()
    {
        return this.server.getURI().resolve(SERVER_PATH);
    }

    void start() throws Exception {
        ServletContextHandler handler = createServletAndHandler();
        server.setHandler(handler);

        server.start();
        System.out.println("Server started at port " + server.getURI());
    }

    private ServletContextHandler createServletAndHandler() {
        JettyWebSocketServlet servlet = new JettyWebSocketServlet() {
            @Override
            protected void configure(JettyWebSocketServletFactory factory) {
                factory.setCreator(new ServerSocketCreator());
            }
        };
        ServletContextHandler handler = new ServletContextHandler();
        handler.addServlet(
                new ServletHolder(servlet),
                "/"
        );
        handler.setContextPath(SERVER_PATH);
        JettyWebSocketServletContainerInitializer.configure(handler, null);
        return handler;
    }

    public static class ServerSocketCreator implements JettyWebSocketCreator {
        @Override
        public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp) {
            return new ServerSocket();
        }
    }

    @WebSocket
    public static class ServerSocket {
        private static final Logger LOG = LoggerFactory.getLogger(ServerSocket.class);
        private Session session;
        private ByteBufferAccumulator byteBufferAccumulator = new ByteBufferAccumulator();

        @OnWebSocketConnect
        public void onWebSocketConnect(Session session) {
            this.session = session;

            session.setInputBufferSize(BUFFER_SIZE);
            session.setOutputBufferSize(BUFFER_SIZE);
        }

        // @OnWebSocketMessage
        public void onWebSocketMessage(Session session, ByteBuffer message) throws IOException
        {
            if (DemoClient.check("Server receiving ", message))
            {
                session.getRemote().sendBytes(message);
            }
        }

        @OnWebSocketFrame
        public void onWebSocketFrame(Session session, Frame frame) throws IOException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onWebSocketFrame(): {}", frame);
            Frame.Type webSocketFrameType = frame.getType();
            // BROKEN TEST: does not check if CONTINUATION belongs to a previous a BINARY (fin=false) (and not a TEXT) frame.
            if ( frame.hasPayload() && webSocketFrameType == Frame.Type.CONTINUATION || webSocketFrameType == Frame.Type.BINARY ) {
                ByteBuffer buffer = frame.getPayload();
                byteBufferAccumulator.copyBuffer(buffer);
                if (frame.isFin()) {
                    // take complete ByteBuffer,
                    ByteBuffer completeMessage = byteBufferAccumulator.takeByteBuffer();
                    try {
                        if (DemoClient.check("Server receiving ", completeMessage)) {
                            session.getRemote().sendBytes(buffer);
                        }
                    } finally {
                        byteBufferAccumulator.getByteBufferPool().release(completeMessage);
                    }
                }
            }
        }
    }

}
