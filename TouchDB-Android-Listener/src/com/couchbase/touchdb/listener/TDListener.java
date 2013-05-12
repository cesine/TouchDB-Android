package com.couchbase.touchdb.listener;

import Acme.Serve.Serve;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDServer;
import com.couchbase.touchdb.router.TDURLStreamHandlerFactory;

public class TDListener implements Runnable {

    private Handler handler;
    private HandlerThread handlerThread;
    private Thread thread;
    private TDServer server;
    private TDHTTPServer httpServer;

    private int serverresult;

    // static inializer to ensure that touchdb:// URLs are handled properly
    {
        TDURLStreamHandlerFactory.registerSelfIgnoreError();
    }

    public TDListener(TDServer server, int port) {
        this.server = server;
        this.httpServer = new TDHTTPServer();
        this.httpServer.setServer(server);
        this.httpServer.setListener(this);
        this.httpServer.setPort(port);
        TurnOffServerRunable turnOffRunable = new TurnOffServerRunable();
        turnOffRunable.setServer(this.httpServer);
        Runtime.getRuntime().addShutdownHook(new Thread(turnOffRunable));
    }

    @Override
    public void run() {
        try {
            serverresult = httpServer.serve();
        } finally {
            handlerThread.quit();
            handlerThread = null;
            handler = null;
        }
    }

    public void start() {
        handlerThread = new HandlerThread("TDListenerHandlerThread");
        handlerThread.start();
        Looper looper = handlerThread.getLooper();
        handler = new Handler(looper);
        thread = new Thread(this);
        thread.start();
    }

    public void stop() {
        httpServer.notifyStop();
    }

    public void onServerThread(Runnable r) {
        handler.post(r);
    }

    public String getStatus() {
        // String status = this.httpServer.getServerInfo();
        return "" + serverresult;
    }
    /**
     * Examle of shut down from Tiny Java Server 
     * final MyServ srv = new MyServ();
        // setting aliases, for an optional file servlet
                    Acme.Serve.Serve.PathTreeDictionary aliases = new Acme.Serve.Serve.PathTreeDictionary();
                    aliases.put("/*", new java.io.File("C:\\temp"));
        //  note cast name will depend on the class name, since it is anonymous class
                    srv.setMappingTable(aliases);
        // setting properties for the server, and exchangeable Acceptors
        java.util.Properties properties = new java.util.Properties();
        properties.put("port", 80);
        properties.setProperty(Acme.Serve.Serve.ARG_NOHUP, "nohup");
        srv.arguments = properties;
        srv.addDefaultServlets(null); // optional file servlet
        srv.addServlet("/myservlet", new MyServlet()); // optional
        // the pattern above is exact match, use /myservlet/* for mapping any path startting with /myservlet (Since 1.93)
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
          public void run() {
            srv.notifyStop();
            srv.destroyAllServlets();
          }
        }));
        srv.serve();
     * 
     * http://tjws.sourceforge.net/
     * 
     */
    public class TurnOffServerRunable implements Runnable{
      private TDHTTPServer server;
      
      public void setServer(TDHTTPServer server){
        this.server = server;
      }
      @Override
      public void run() {
        Log.e(TDDatabase.TAG, "Shutting down touchdb listeners server");
        this.server.notifyStop();
        this.server.destroyAllServlets();        
      }
      
    }
}
