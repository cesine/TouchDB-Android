package com.couchbase.touchdb.listener;

import java.util.Properties;

import Acme.Serve.Serve;

import android.util.Log;

import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDServer;

@SuppressWarnings("serial")
public class TDHTTPServer extends Serve {

    public static final String TDSERVER_KEY = "TDServer";

    private Properties props;
    private TDServer server;
    private TDListener listener;
    private TDHTTPServlet servlet;
    public TDHTTPServer() {
        props = new Properties();
    }

    public void setServer(TDServer server) {
        this.server = server;
    }

    public void setListener(TDListener listener) {
        this.listener = listener;
    }

    public void setPort(int port) {
        props.put("port", port);
    }

    @Override
    public int serve() {
        //pass our custom properties in
        this.arguments = props;

        //pass in the tdserver to the servlet
        servlet = new TDHTTPServlet();
        servlet.setServer(server);
        servlet.setListener(listener);

        this.addServlet("/", servlet);
        
        int result = super.serve();
        return result;
    }

    @Override
    public void notifyStop() {
      Log.d(TDDatabase.TAG, "Notifying stop on the TDHTTPServer");
      super.notifyStop();
      super.destroyAllServlets();
    }
    

}
