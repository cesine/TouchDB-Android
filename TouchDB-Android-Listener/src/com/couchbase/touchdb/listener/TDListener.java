package com.couchbase.touchdb.listener;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ScheduledExecutorService;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDServer;
import com.couchbase.touchdb.router.TDURLStreamHandlerFactory;

public class TDListener implements Runnable {

    private Thread thread;
    private TDServer server;
    private TDHTTPServer httpServer;
    private int listenPort;
    private int serverStatus;

    //static inializer to ensure that touchdb:// URLs are handled properly
    {
        TDURLStreamHandlerFactory.registerSelfIgnoreError();
    }

    /**
     * CBLListener constructor
     *
     * @param server the CBLServer instance
     * @param suggestedPort the suggested port to use.  if not available, will hunt for a new port.
     *                      and this port can be discovered by calling getListenPort()
     */
    public TDListener(TDServer server, int suggestedPort) {
        this.server = server;
        this.httpServer = new TDHTTPServer();
        this.httpServer.setServer(server);
        this.httpServer.setListener(this);
        this.listenPort = discoverEmptyPort(suggestedPort);
        this.httpServer.setPort(this.listenPort);
    }

    /**
     * Hunt for an empty port starting from startPort by binding a server
     * socket until it succeeds w/o getting an exception.  Once found, close
     * the server socket so that port is available.
     * <p/>
     * Caveat: yes, there is a tiny race condition in the sense that the
     * caller could receive a port that was bound by another thread
     * before it has a chance to bind)
     *
     * @param startPort - the port to start hunting at, eg: 5984
     * @return the port that was bound.  (or a runtime exception is thrown)
     */
    public int discoverEmptyPort(int startPort) {
        for (int curPort = startPort; curPort < 65536; curPort++) {
            try {
                ServerSocket socket = new ServerSocket(curPort);
                socket.close();
                return curPort;
            } catch (IOException e) {
                Log.d(TDDatabase.TAG, "Could not bind to port: " + curPort + ".  Trying another port.");
            }

        }
        throw new RuntimeException("Could not find empty port starting from: " + startPort);
    }

    @Override
    public void run() {
        this.serverStatus = httpServer.serve();
    }

    public void start() {
        thread = new Thread(this);
        thread.start();
    }

    public void stop() {
        httpServer.notifyStop();
    }

    public void onServerThread(Runnable r) {
        ScheduledExecutorService workExecutor = server.getWorkExecutor();
        workExecutor.submit(r);
    }

    public int serverStatus() {
        return this.serverStatus;
    }

    public int getListenPort() {
        return listenPort;
    }
}
