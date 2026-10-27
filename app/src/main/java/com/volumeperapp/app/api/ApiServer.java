package com.volumeperapp.app.api;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The socket half of the API: accept, read one request, write one response,
 * close. No keep-alive — the callers are scripts and PhoneAPI making a call
 * every so often, and a connection that is always closed cannot be left
 * half-read. Each connection goes to a small pool, so one slow caller cannot
 * stop the next being served.
 */
final class ApiServer {

    private static final String TAG = "VPA.Api";
    private static final int WORKERS = 4;
    /** A client that connects and says nothing is dropped rather than held. */
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final int BACKLOG = 16;

    private final ApiRouter router;
    private final String bindAddress;
    private final int port;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService workers;

    ApiServer(ApiRouter router, String bindAddress, int port) {
        this.router = router;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    boolean isRunning() {
        return running.get();
    }

    String address() {
        return bindAddress + ":" + port;
    }

    /** @throws IOException when the port is taken. */
    synchronized void start() throws IOException {
        if (running.get()) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(InetAddress.getByName(bindAddress), port),
                BACKLOG);
        serverSocket = socket;
        workers = Executors.newFixedThreadPool(WORKERS);
        running.set(true);
        Thread accept = new Thread(() -> acceptLoop(socket), "vpa-api-accept");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "listening on " + address());
    }

    synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // Closing is the point; a failure to close a closed socket is not news.
        }
        if (workers != null) workers.shutdownNow();
        serverSocket = null;
        workers = null;
        Log.i(TAG, "stopped");
    }

    private void acceptLoop(ServerSocket socket) {
        while (running.get()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                // A close() while blocked in accept() lands here: a stop, not a fault.
                if (running.get()) Log.w(TAG, "accept failed", e);
                return;
            }
            ExecutorService pool = workers;
            if (pool == null || pool.isShutdown()) {
                closeQuietly(client);
                return;
            }
            try {
                pool.execute(() -> serve(client));
            } catch (Exception e) {
                closeQuietly(client);
            }
        }
    }

    private void serve(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            Http.Response response;
            try {
                Http.Request request = Http.parse(in);
                if (request == null) return;
                response = router.handle(request);
            } catch (ApiException e) {
                response = Http.Response.error(e.status, e.getMessage(), e.hint);
            }
            write(out, response);
        } catch (IOException e) {
            // Timeouts and callers that hang up: nothing to answer.
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private static void write(OutputStream out, Http.Response r) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason()).append("\r\n")
                .append("Content-Type: ").append(r.contentType).append("\r\n")
                .append("Content-Length: ").append(r.body.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n");
        for (Map.Entry<String, String> h : r.extraHeaders.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(r.body);
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
