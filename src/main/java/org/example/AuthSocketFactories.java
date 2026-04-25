package org.example;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.util.Objects;

public final class AuthSocketFactories {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final TimedClientSocketFactory CLIENT_FACTORY = new TimedClientSocketFactory();
    private static final TimedServerSocketFactory SERVER_FACTORY = new TimedServerSocketFactory();

    private AuthSocketFactories() {
    }

    public static RMIClientSocketFactory clientFactory() {
        return CLIENT_FACTORY;
    }

    public static RMIServerSocketFactory serverFactory() {
        return SERVER_FACTORY;
    }

    public static final class TimedClientSocketFactory implements RMIClientSocketFactory, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            return socket;
        }

        @Override
        public boolean equals(Object other) {
            return other != null && getClass() == other.getClass();
        }

        @Override
        public int hashCode() {
            return Objects.hash(getClass().getName());
        }
    }

    public static final class TimedServerSocketFactory implements RMIServerSocketFactory, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return new ServerSocket(port);
        }

        @Override
        public boolean equals(Object other) {
            return other != null && getClass() == other.getClass();
        }

        @Override
        public int hashCode() {
            return Objects.hash(getClass().getName());
        }
    }
}
