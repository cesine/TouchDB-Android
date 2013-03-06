/**
 * Original iOS version by  Jens Alfke
 * Ported to Android by Marty Schoch
 *
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.couchbase.touchdb;

import java.util.List;
import java.util.Map;

import org.codehaus.jackson.map.ObjectWriter;

import android.util.Log;

/**
 * A request/response/document body, stored as either JSON or a Map<String,Object>
 */
public class TDBody {

    private byte[] json;
    private Object object;
    private boolean error = false;

    public TDBody(byte[] json) {
        this.json = json;
    }

    public TDBody(Map<String, Object> properties) {
        this.object = properties;
    }

    public TDBody(List<?> array) {
        this.object = array;
    }

    public static TDBody bodyWithProperties(Map<String,Object> properties) {
        TDBody result = new TDBody(properties);
        return result;
    }

    public static TDBody bodyWithJSON(byte[] json) {
        TDBody result = new TDBody(json);
        return result;
    }

    public boolean isValidJSON() {
        // Yes, this is just like asObject except it doesn't warn.
        if(json == null && !error) {
            try {
                json = TDServer.getObjectMapper().writeValueAsBytes(object);
            } catch (Exception e) {
                Log.w(TDDatabase.TAG, "Error in isValidJSON ", e);
                error = true;
            }
        }
        return (object != null);
    }

    public byte[] getJson() {
        if(json == null && !error) {
            try {
                json = TDServer.getObjectMapper().writeValueAsBytes(object);
            } catch (Exception e) {
                Log.w(TDDatabase.TAG, "TDBody: couldn't convert JSON", e);
                error = true;
            }
        }
        return json;
    }

    public byte[] getPrettyJson() {
        Object properties = getObject();
        if(properties != null) {
            ObjectWriter writer = TDServer.getObjectMapper().writerWithDefaultPrettyPrinter();
            try {
                json = writer.writeValueAsBytes(properties);
            } catch (Exception e) {
                Log.w(TDDatabase.TAG, "Error in getPrettyJson", e);
                error = true;
            }
        }
        return getJson();
    }

    public String getJSONString() {
        return new String(getJson());
    }

    public Object getObject() {
        if(object == null && !error) {
            try {
                if(json != null) {
                    object = TDServer.getObjectMapper().readValue(json, Map.class);
                }
            } catch (Exception e) {
                Log.w(TDDatabase.TAG, "TDBody: couldn't parse JSON: " + new String(json), e);
                error = true;
            }
        }
        return object;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getProperties() {
        Object object = getObject();
        if(object instanceof Map) {
            return (Map<String,Object>)object;
        }
        return null;
    }

    public Object getPropertyForKey(String key) {
        Map<String, Object> theProperties = getProperties();
        if (theProperties == null) {
            Log.e("TDDatabase", "theProperties was null");
            /* TODO test this */
            return null;
        }
        if (theProperties == null) {
            Log.e("key", "key was null");
            /* TODO test this */
            return null;
        }
        /* 
         * Sometimes the key or theProperties is null 
         * 
         * 02-24 17:16:37.980: V/TDDatabase(5291): Buffer size is 128
02-24 17:16:37.990: E/TDDatabase(5291): Exception in TDRouter on null
02-24 17:16:37.990: E/TDDatabase(5291): java.lang.reflect.InvocationTargetException
02-24 17:16:37.990: E/TDDatabase(5291):   at java.lang.reflect.Method.invokeNative(Native Method)
02-24 17:16:37.990: E/TDDatabase(5291):   at java.lang.reflect.Method.invoke(Method.java:511)
02-24 17:16:37.990: E/TDDatabase(5291):   at com.couchbase.touchdb.router.TDRouter.start(TDRouter.java:390)
02-24 17:16:37.990: E/TDDatabase(5291):   at com.couchbase.touchdb.listener.TDHTTPServlet.service(TDHTTPServlet.java:108)
02-24 17:16:37.990: E/TDDatabase(5291):   at javax.servlet.http.HttpServlet.service(HttpServlet.java:802)
02-24 17:16:37.990: E/TDDatabase(5291):   at Acme.Serve.Serve$ServeConnection.runServlet(Serve.java:2347)
02-24 17:16:37.990: E/TDDatabase(5291):   at Acme.Serve.Serve$ServeConnection.parseRequest(Serve.java:2266)
02-24 17:16:37.990: E/TDDatabase(5291):   at Acme.Serve.Serve$ServeConnection.run(Serve.java:2056)
02-24 17:16:37.990: E/TDDatabase(5291):   at Acme.Utils$ThreadPool$PooledThread.run(Utils.java:1223)
02-24 17:16:37.990: E/TDDatabase(5291):   at java.lang.Thread.run(Thread.java:856)
02-24 17:16:37.990: E/TDDatabase(5291): Caused by: java.lang.NullPointerException
02-24 17:16:37.990: E/TDDatabase(5291):   at com.couchbase.touchdb.TDBody.getPropertyForKey(TDBody.java:124)
02-24 17:16:37.990: E/TDDatabase(5291):   at com.couchbase.touchdb.router.TDRouter.update(TDRouter.java:1194)
02-24 17:16:37.990: E/TDDatabase(5291):   at com.couchbase.touchdb.router.TDRouter.update(TDRouter.java:1240)
02-24 17:16:37.990: E/TDDatabase(5291):   at com.couchbase.touchdb.router.TDRouter.do_POST_Database(TDRouter.java:677)
02-24 17:16:37.990: E/TDDatabase(5291):   ... 10 more
         */
        return theProperties.get(key);
    }

    public boolean isError() {
        return error;
    }
}
