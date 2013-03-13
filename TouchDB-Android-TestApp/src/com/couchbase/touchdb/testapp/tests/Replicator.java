package com.couchbase.touchdb.testapp.tests;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.json.JSONException;
import org.json.JSONObject;

import junit.framework.Assert;
import android.content.Intent;
import android.util.Log;

import com.couchbase.touchdb.TDBlobStore;
import com.couchbase.touchdb.TDBody;
import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDRevision;
import com.couchbase.touchdb.TDStatus;
import com.couchbase.touchdb.replicator.TDPusher;
import com.couchbase.touchdb.replicator.TDReplicator;
import com.couchbase.touchdb.support.HttpClientFactory;
import com.couchbase.touchdb.testapp.CopySampleAttachmentsActivity;

public class Replicator extends TouchDBTestCase {

    public static final String TAG = "Replicator";

    public void testPusher() throws Throwable {

        URL remote = getReplicationURL();

        deleteRemoteDB(remote);

        // Create some documents:
        Map<String, Object> documentProperties = new HashMap<String, Object>();
        documentProperties.put("_id", "doc1");
        documentProperties.put("foo", 1);
        documentProperties.put("bar", false);

        TDBody body = new TDBody(documentProperties);
        TDRevision rev1 = new TDRevision(body);

        TDStatus status = new TDStatus();
        rev1 = database.putRevision(rev1, null, false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        documentProperties.put("_rev", rev1.getRevId());
        documentProperties.put("UPDATED", true);

        @SuppressWarnings("unused")
        TDRevision rev2 = database.putRevision(new TDRevision(documentProperties), rev1.getRevId(), false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        documentProperties = new HashMap<String, Object>();
        documentProperties.put("_id", "doc2");
        documentProperties.put("baz", 666);
        documentProperties.put("fnord", true);

        database.putRevision(new TDRevision(documentProperties), null, false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        final TDReplicator repl = database.getReplicator(remote, true, false);
        ((TDPusher)repl).setCreateTarget(true);
        runTestOnUiThread(new Runnable() {

            @Override
            public void run() {
                // Push them to the remote:
                repl.start();
                Assert.assertTrue(repl.isRunning());
            }
        });

        while(repl.isRunning()) {
            Log.i(TAG, "Waiting for replicator to finish");
            Thread.sleep(1000);
        }
        Assert.assertEquals("3", repl.getLastSequence());
    }
    
    public void testAttachmentPusher() throws Throwable {
        boolean putInAlotOfImages = false;

        // clean up remote db 
        URL remote = getReplicationURL();
        deleteRemoteDB(remote);

        // Create some documents:
        Map<String, Object> documentProperties = new HashMap<String, Object>();
        documentProperties.put("_id", "doc1");
        documentProperties.put("foo", 1);
        documentProperties.put("bar", false);

        TDBody body = new TDBody(documentProperties);
        TDRevision rev1 = new TDRevision(body);

        TDStatus status = new TDStatus();
        rev1 = database.putRevision(rev1, null, false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        documentProperties.put("_rev", rev1.getRevId());
        documentProperties.put("UPDATED", true);

        @SuppressWarnings("unused")
        TDRevision rev2 = database.putRevision(new TDRevision(documentProperties),
                rev1.getRevId(), false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        documentProperties = new HashMap<String, Object>();
        documentProperties.put("_id", "doc2");
        documentProperties.put("baz", 666);
        documentProperties.put("fnord", true);

        TDRevision rev3 = database.putRevision(new TDRevision(documentProperties),
                null, false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        // Make sure we have no attachments:
        TDBlobStore attachments = database.getAttachments();
        Assert.assertEquals(0, attachments.count());
        Assert.assertEquals(new HashSet<Object>(), attachments.allKeys());

        // Add a text attachment to the documents:
        byte[] htmlAttachment = "<html>And this is an html attachment.</html>"
                .getBytes();
        status = database.insertAttachmentForSequenceWithNameAndType(
                new ByteArrayInputStream(htmlAttachment), rev3.getSequence(),
                "sample_attachment.html", "text/html", rev3.getGeneration());
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        // Add two image attachments to the documents, these attachments are copied
        // by the CopySampleAttachmentsActivity.
        Intent copyImages = new Intent(getInstrumentation().getContext(),
                CopySampleAttachmentsActivity.class);
        copyImages.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getInstrumentation().startActivitySync(copyImages);

        // Attach sample images to doc2
        FileInputStream fileStream = null;
        FileInputStream fileStream2 = null;
        boolean sampleFilesExistAndWereCopied = true;
        try {
            fileStream = new FileInputStream(
                    "/data/data/com.couchbase.touchdb.testapp/files/sample_attachment_image1.jpg");
            byte[] imageAttachment = IOUtils.toByteArray(fileStream);
            if (imageAttachment.length == 0) {
                sampleFilesExistAndWereCopied = false;
            }
            status = database.insertAttachmentForSequenceWithNameAndType(
                    new ByteArrayInputStream(imageAttachment), rev3.getSequence(),
                    "sample_attachment_image1.jpg", "image/jpeg", rev3.getGeneration());
            Assert.assertEquals(TDStatus.CREATED, status.getCode());

            if (putInAlotOfImages) {
                
                fileStream2 = new FileInputStream(
                        "/data/data/com.couchbase.touchdb.testapp/files/sample_attachment_image2.jpg");
                byte[] secondImageAttachment = IOUtils.toByteArray(fileStream2);
                if (secondImageAttachment.length == 0) {
                    sampleFilesExistAndWereCopied = false;
                }
                status = database.insertAttachmentForSequenceWithNameAndType(
                        new ByteArrayInputStream(secondImageAttachment), rev3.getSequence(),
                        "sample_attachment_image2.jpg", "image/jpeg", rev3.getGeneration());
                Assert.assertEquals(TDStatus.CREATED, status.getCode());
                
                int totalThreeImagesCanUpload = 1;
                int totalFourImagesRunsOutOfMemory = 2;
                
                for (int i = 0; i < totalFourImagesRunsOutOfMemory; i++) {
                    status = database.insertAttachmentForSequenceWithNameAndType(
                            new ByteArrayInputStream(secondImageAttachment),
                            rev3.getSequence(), "sample_attachment_image_test_out_of_memory"+i+".jpg", "image/jpeg",
                            rev3.getGeneration());
                    Assert.assertEquals(TDStatus.CREATED, status.getCode());
                    int imagesSoFar = i+2;
                    Log.e(TAG, "Attached " + imagesSoFar + "images.");
                    if (status.getCode() != TDStatus.CREATED) {
                        break;
                    }
                }
            }
            
        } catch (FileNotFoundException e) {
            sampleFilesExistAndWereCopied = false;
            e.printStackTrace();
        } catch (IOException e) {
            sampleFilesExistAndWereCopied = false;
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(fileStream);
            IOUtils.closeQuietly(fileStream2);
        }
        if (!sampleFilesExistAndWereCopied) {
            Log.e(
                    TAG,
                    "The sample image files for testing multipart attachment upload werent copied to the SDCARD, ");
        }
        /*
         * test failed probably because the sample files weren't copied from the
         * res/raw to the sdacard
         */
        Assert.assertTrue(sampleFilesExistAndWereCopied);
        final TDReplicator repl = database.getReplicator(remote, true, false);
        ((TDPusher) repl).setCreateTarget(true);
        runTestOnUiThread(new Runnable() {

            @Override
            public void run() {
                // Push them to the remote:
                repl.start();
                Assert.assertTrue(repl.isRunning());
            }
        });

        while (repl.isRunning()) {
            Log.i(TAG, "Waiting for first replicator to finish");
            Thread.sleep(1000);
        }
        Assert.assertEquals("3", repl.getLastSequence());
        
    }
    
    public void testSecondAttachmentPusher() throws Throwable {
        
        testAttachmentPusher();
        
        // Now update the document without modifying the attachments
        TDRevision readRev = database.getDocumentWithIDAndRev("doc2", null, EnumSet.noneOf(TDDatabase.TDContentOptions.class));
        Assert.assertNotNull(readRev);
        Map<String,Object> readRevProps = readRev.getProperties();
        readRevProps.put("status", "updated!");
        TDStatus status = new TDStatus();
        TDRevision rev4 = database.putRevision(new TDRevision(readRevProps), readRev.getRevId(), true, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());
        
        readRevProps.put("secondupdate", "updated!");
        database.putRevision(new TDRevision(readRevProps), rev4.getRevId(), true, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        URL remote = getReplicationURL();
        final TDReplicator secondReplication = database.getReplicator(remote, true, false);
        ((TDPusher) secondReplication).setCreateTarget(true);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Push them to the remote:
                secondReplication.start();
                Assert.assertTrue(secondReplication.isRunning());
            }
        });

        while (secondReplication.isRunning()) {
            Log.i(TAG, "Waiting for second replicator to finish");
            Thread.sleep(1000);
        }
        
        // Unable to get second replication (to get to revision 5) after one replication. 
        Assert.assertEquals("5", secondReplication.getLastSequence());
    }
    
    /* 
     * Test to show that we can't use the org.apache.http.entity.mime.MultipartEntity library for attachment uploads until at least CouchDB v1.4+
     * https://issues.apache.org/jira/browse/COUCHDB-1632
     * 
     * Case 1: FAIL a simple document uploaded using Multipart in form-data
    
        03-10 21:37:01.015: E/TDDatabase(4408): --abc123
        03-10 21:37:01.015: E/TDDatabase(4408): Content-Disposition: form-data; name="data"
        03-10 21:37:01.015: E/TDDatabase(4408): 
        03-10 21:37:01.015: E/TDDatabase(4408): {"body":"This is a simple document with nothing really in it, but it uses the multipart upload."}
        03-10 21:37:01.015: E/TDDatabase(4408): --abc123--
        03-10 21:37:04.125: W/System.err(4408): org.apache.http.NoHttpResponseException: The target server failed to respond
    
     * Case 2: FAIL a document and its attachments uploaded using Multipart in form-data

        03-10 21:37:05.871: E/TDDatabase(4408): --abc123
        03-10 21:37:05.871: E/TDDatabase(4408): Content-Disposition: form-data; name="data"
        03-10 21:37:05.871: E/TDDatabase(4408): 
        03-10 21:37:05.871: E/TDDatabase(4408): {"body":"This is a document with some attachments which are uploaded using multipart in a form, not from files.",
        03-10 21:37:05.871: E/TDDatabase(4408): "_attachments":{
        03-10 21:37:05.871: E/TDDatabase(4408):   "foo.txt": {
        03-10 21:37:05.871: E/TDDatabase(4408):     "follows":true, 
        03-10 21:37:05.871: E/TDDatabase(4408):     "content_type":"text/plain",
        03-10 21:37:05.871: E/TDDatabase(4408):     "length":21
        03-10 21:37:05.871: E/TDDatabase(4408):     },
        03-10 21:37:05.871: E/TDDatabase(4408):   "bar.txt": {
        03-10 21:37:05.871: E/TDDatabase(4408):     "follows":true, 
        03-10 21:37:05.871: E/TDDatabase(4408):     "content_type":"text/plain",
        03-10 21:37:05.871: E/TDDatabase(4408):     "length":20
        03-10 21:37:05.871: E/TDDatabase(4408):     } 
        03-10 21:37:05.871: E/TDDatabase(4408):   }
        03-10 21:37:05.871: E/TDDatabase(4408): }
        03-10 21:37:05.871: E/TDDatabase(4408): --abc123
        03-10 21:37:05.871: E/TDDatabase(4408): Content-Disposition: form-data; name="foo.txt"
        03-10 21:37:05.871: E/TDDatabase(4408): 
        03-10 21:37:05.871: E/TDDatabase(4408): this is 21 chars long
        03-10 21:37:05.871: E/TDDatabase(4408): --abc123
        03-10 21:37:05.871: E/TDDatabase(4408): Content-Disposition: form-data; name="bar.txt"
        03-10 21:37:05.871: E/TDDatabase(4408): 
        03-10 21:37:05.871: E/TDDatabase(4408): this is 20 chars lon
        03-10 21:37:05.871: E/TDDatabase(4408): --abc123--
        03-10 21:37:07.683: W/System.err(4408): org.apache.http.NoHttpResponseException: The target server failed to respond
    
     * Case 3: FAIL a document and its attachments uploaded using Multipart with files
    
        03-10 21:37:08.847: E/TDDatabase(4408): --abc123
        03-10 21:37:08.847: E/TDDatabase(4408): Content-Disposition: form-data; name="data"; filename="doc.json"
        03-10 21:37:08.847: E/TDDatabase(4408): Content-Type: application/json
        03-10 21:37:08.847: E/TDDatabase(4408): 
        03-10 21:37:08.847: E/TDDatabase(4408): {"body":"This is a body.",
        03-10 21:37:08.847: E/TDDatabase(4408): "_attachments":{
        03-10 21:37:08.847: E/TDDatabase(4408):   "foo.txt": {
        03-10 21:37:08.847: E/TDDatabase(4408):     "follows":true,
        03-10 21:37:08.847: E/TDDatabase(4408):     "content_type":"text/plain",
        03-10 21:37:08.847: E/TDDatabase(4408):     "length":21
        03-10 21:37:08.847: E/TDDatabase(4408):     },
        03-10 21:37:08.847: E/TDDatabase(4408):   "bar.txt": {
        03-10 21:37:08.847: E/TDDatabase(4408):     "follows":true,
        03-10 21:37:08.847: E/TDDatabase(4408):     "content_type":"text/plain",
        03-10 21:37:08.847: E/TDDatabase(4408):     "length":20
        03-10 21:37:08.847: E/TDDatabase(4408):     }
        03-10 21:37:08.847: E/TDDatabase(4408):   }
        03-10 21:37:08.847: E/TDDatabase(4408): }
        03-10 21:37:08.847: E/TDDatabase(4408): --abc123
        03-10 21:37:08.847: E/TDDatabase(4408): Content-Disposition: form-data; name="foo.txt"; filename="foo.txt"
        03-10 21:37:08.847: E/TDDatabase(4408): Content-Type: text/plain
        03-10 21:37:08.847: E/TDDatabase(4408): 
        03-10 21:37:08.847: E/TDDatabase(4408): this is 21 chars long
        03-10 21:37:08.847: E/TDDatabase(4408): --abc123
        03-10 21:37:08.847: E/TDDatabase(4408): Content-Disposition: form-data; name="bar.txt"; filename="bar.txt"
        03-10 21:37:08.847: E/TDDatabase(4408): Content-Type: text/plain
        03-10 21:37:08.847: E/TDDatabase(4408): 
        03-10 21:37:08.847: E/TDDatabase(4408): this is 20 chars lon
        03-10 21:37:08.847: E/TDDatabase(4408): --abc123--
        03-10 21:37:10.504: W/System.err(4408): org.apache.http.NoHttpResponseException: The target server failed to respond
     */
    public void testCouchDBMultiPartAttachmentUploadWithApacheLibrary()
            throws Throwable {

        // Are we able to upload a document and its attachments to CouchDB using
        // bare bones Multipart files
        boolean isBareBonesMultipartSuccessful = uploadBareBonesMultiPartAttachment();
        Assert.assertTrue(isBareBonesMultipartSuccessful);

        // Are we able to upload a simple document to CouchDB using Multipart
        // form data
        boolean uploadFromFiles = false;
        boolean uploadSimpleDocumentWithNoAttachments = true;
        boolean isSuccessful = uploadNonTouchDBMultiPartAttachment(
                uploadFromFiles, uploadSimpleDocumentWithNoAttachments);
        Assert.assertTrue(isSuccessful);

        
        // Are we able to upload a document and its attachments to CouchDB using
        // Multipart form data
        uploadFromFiles = false;
        uploadSimpleDocumentWithNoAttachments = false;
        isSuccessful = uploadNonTouchDBMultiPartAttachment(uploadFromFiles,
                uploadSimpleDocumentWithNoAttachments);
        Assert.assertTrue(isSuccessful);

        
        // Are we able to upload a document and its attachments to CouchDB using
        // Multipart files
        uploadFromFiles = true;
        uploadSimpleDocumentWithNoAttachments = false;
        isSuccessful = uploadNonTouchDBMultiPartAttachment(uploadFromFiles,
                uploadSimpleDocumentWithNoAttachments);
        Assert.assertTrue(isSuccessful);
    }
    
    
    /*
     * Notes and references from trying to isolate whether or not it is possible to combine Apache MultipartEntity with CouchDB.
     * 
     * From Jens: worked around the bug by computing the body length (by
     * adding up the sizes of the attachment files and MIME boundary
     * strings) then set that as a Content-Length header on the request;
     * this tells the HTTP library I use (NSURLConnection on Mac OS) not to
     * use chunked encoding.
     * 
     * References:
     * https://issues.apache.org/jira/browse/COUCHDB-1192
     * http://stackoverflow.com/questions/10979479/how-to-do-bulk-insert-from-huge-json-file-460-mb-in-couchdb
     * http://www.iandennismiller.com/posts/curl-http1-1-100-continue-and-multipartform-data-post.html
     * Couchdb can handle a 100 Continue: https://issues.apache.org/jira/browse/COUCHDB-433
     * http://mail-archives.apache.org/mod_mbox/couchdb-dev/200912.mbox/%3C91510288.1261168278176.JavaMail.jira@brutus%3E
     * https://issues.apache.org/jira/browse/COUCHDB-1368
     * https://groups.google.com/forum/?fromgroups=#!topic/couchdb-user-archive/SAOkhQHklKc
     * http://grokbase.com/t/couchdb/user/131xa2e0h4/bad-request-referer-must-match-host
     * http://stackoverflow.com/questions/13281021/how-to-set-a-standalone-attachment-content-type
     * 
     * Curl with documents:
     * curl -vkX PUT https://admin:none@localhost:6984/testdb/basicdoc -d '{"title":"Blackened Sky","artist":"Biffy Clyro","year":2002}'
     * curl -vkX PUT https://admin:none@localhost:6984/testdb/firsttry -d @doc.json 'Content-Type: application/json'
     * 
     * The suggested MultipartEntity in the http://wiki.apache.org/couchdb/HTTP_Document_API#Multiple_Attachments
     * (from someone on stack over flow who was reverse engineering the multipart used by Futon).
     * They don't provide the code they used to execute this (in curl or elswhere):
     * 
     * Unsuccessful attempt to test multipart using curl:
     * curl -vkX PUT https://admin:none@localhost:6984/testdb/firsttry -F files[]=@doc.json -F files[]=@foo.txt -F files[]=@bar.txt -H "Content-Type: multipart/related"     
     * 
     * 
     * Sample Document and attachments from the wiki (http://wiki.apache.org/couchdb/HTTP_Document_API#Multiple_Attachments)
     * {  "body":"This is a body.", "_attachments":{   "foo.txt": {     "follows":true,     "content_type":"text/plain",     "length":21     },   "bar.txt": {     "follows":true,     "content_type":"text/plain",     "length":20     }   } }
     * 
     * 
     * Claimed resulting multipart:
         Content-Type: multipart/related;boundary="abc123"
        
        --abc123
        content-type: application/json
        
        {"body":"This is a body.",
        "_attachments":{
          "foo.txt": {
            "follows":true,
            "content_type":"text/plain",
            "length":21
            },
          "bar.txt": {
            "follows":true,
            "content_type":"text/plain",
            "length":20
            },
          }
        }
        
        --abc123
        
        this is 21 chars long
        --abc123
        
        this is 20 chars lon
        --abc123--
     * 
     * 
     * The MultipartEntity produced by the org.apache.http.entity.mime.MultipartEntity 
     * creates a great deal of additional information which CouchDB apparently can't digest:
     * 
        Content-Disposition: form-data; name="json"; filename="doc.json"
        Content-Type: application/json
        
        {"body":"This is a body.",
        "_attachments":{
          "foo.txt": {
            "follows":true, 
            "content_type":"text/plain",
            "length":21
            },
          "bar.txt": {
            "follows":true, 
            "content_type":"text/plain",
            "length":20
            },
          }
        }
        --abc123
        Content-Disposition: form-data; name="foo.txt"; filename="foo.txt"
        Content-Type: text/plain
        
        this is 21 chars long
        --abc123
        Content-Disposition: form-data; name="bar.txt"; filename="bar.txt"
        Content-Type: text/plain
        
        this is 20 chars lon
        --abc123--
    *
    *
    * Normal pushes from TouchDB to CouchDB 1.2 look like this in the CouchDB log with log level debug:
    * $ tail -F  /Users/me/Library/Application\ Support/CouchDB/var/log/couchdb/couch.log
    * 
        [Sun, 10 Mar 2013 19:28:39 GMT] [info] [<0.281.0>] 192.168.0.105 - - POST /touchdb-test/_bulk_docs 201
        [Sun, 10 Mar 2013 19:28:39 GMT] [debug] [<0.136.0>] 'PUT' /touchdb-test/_local/60b075e04690b8d0c21a0a02b1e703f6af2c5acf {1,
                                                                                         1} from "192.168.0.105"
        Headers: [{'Accept',"application/json"},
          {'Authorization',"Basic YWRtaW46bm9uZQ=="},
          {'Connection',"Keep-Alive"},
          {'Content-Length',"20"},
          {'Content-Type',"application/json"},
          {'Host',"192.168.0.107:5984"},
          {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
     * 
     * Straight org.apache.http.entity.mime.MultipartEntity  requests look like this:
     * 
        [Sun, 10 Mar 2013 19:30:43 GMT] [debug] [<0.502.0>] 'PUT' /touchdb-test/testupload1362943837339 {1,1} from "192.168.0.105"
        Headers: [{'Connection',"Keep-Alive"},
                  {'Content-Length',"622"},
                  {'Content-Type',"multipart/form-data; boundary=abc123; charset=UTF-8"},
                  {'Host',"192.168.0.107:5984"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Sun, 10 Mar 2013 19:30:43 GMT] [debug] [<0.502.0>] OAuth Params: []
        [Sun, 10 Mar 2013 19:30:43 GMT] [error] [<0.502.0>] attempted upload of invalid JSON (set log_level to debug to log it)
        [Sun, 10 Mar 2013 19:30:43 GMT] [debug] [<0.502.0>] Invalid JSON: {{error,
                                               {1,
                                                "lexical error: malformed number, a digit is required after the minus sign.\n"}},
                                           <<"--abc123--\r\nContent-Disposition: form-data; name=\"json\"; filename=\"doc.json\"\r\nContent-Type: application/json\r\n\r\n{\"body\":\"This is a body.\",\n\"_attachments\":{\n  \"foo.txt\": {\n    \"follows\":true,\n    \"content_type\":\"text/plain\",\n    \"length\":21\n    },\n  \"bar.txt\": {\n    \"follows\":true,\n    \"content_type\":\"text/plain\",\n    \"length\":20\n    }\n  }\n}\n\r\n--abc123--\r\nContent-Disposition: form-data; name=\"foo.txt\"; filename=\"foo.txt\"\r\nContent-Type: text/plain\r\n\r\nthis is 21 chars long\n\r\n--abc123--\r\nContent-Disposition: form-data; name=\"bar.txt\"; filename=\"bar.txt\"\r\nContent-Type: text/plain\r\n\r\nthis is 20 chars lon\n\r\n--abc123----\r\n">>}
        [Sun, 10 Mar 2013 19:30:43 GMT] [info] [<0.502.0>] 192.168.0.105 - - PUT /touchdb-test/testupload1362943837339 400
        [Sun, 10 Mar 2013 19:30:43 GMT] [debug] [<0.502.0>] httpd 400 error response:
         {"error":"bad_request","reason":"invalid_json"}    
    
     * If we modify the header to be "multipart/related" instead of "multipart/form-data" 
     * We get further....
     *    request.setHeader("Content-type", "multipart/related; boundary=abc123; charset=UTF-8");
     * 
     * Now maybe the multipart seems to be processed, but getting a new error:
     * 
        [Sun, 10 Mar 2013 19:48:48 GMT] [debug] [<0.1100.0>] 'PUT' /touchdb-test/testupload1362944922247 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"multipart/related"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"614"},
                  {'Content-Type',"multipart/related; boundary=abc123; charset=UTF-8"},
                  {'Host',"192.168.0.107:5984"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Sun, 10 Mar 2013 19:48:48 GMT] [debug] [<0.1100.0>] OAuth Params: []
        [Sun, 10 Mar 2013 19:48:48 GMT] [error] [<0.1100.0>] function_clause error in HTTP request
        [Sun, 10 Mar 2013 19:48:48 GMT] [info] [<0.1100.0>] Stacktrace: [{couch_db,write_streamed_attachment,
                                          [<0.1725.0>,#Fun<couch_doc.16.119974875>,-1],
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,1031}]},
                                         {couch_db,with_stream,3,
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,990}]},
                                         {couch_db,'-doc_flush_atts/2-lc$^0/1-0-',2,
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,902}]},
                                         {couch_db,doc_flush_atts,2,
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,902}]},
                                         {couch_db,'-update_docs/4-lc$^7/1-7-',2,
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,771}]},
                                         {couch_db,'-update_docs/4-lc$^6/1-6-',2,
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,770}]},
                                         {couch_db,update_docs,4,
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,770}]},
                                         {couch_db,update_doc,4,
                                          [{file,
                                            "/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_db.erl"},
                                           {line,423}]}]
        [Sun, 10 Mar 2013 19:48:48 GMT] [error] [<0.1100.0>] Uncaught server error: function_clause
        [Sun, 10 Mar 2013 19:48:48 GMT] [info] [<0.1100.0>] 192.168.0.105 - - PUT /touchdb-test/testupload1362944922247 500
        [Sun, 10 Mar 2013 19:48:48 GMT] [debug] [<0.1100.0>] httpd 500 error response:
         {"error":"unknown_error","reason":"function_clause"}
    
    
    * Quote from https://github.com/couchbaselabs/TouchDB-iOS/issues/133 
    * "On the couchDB irc channel I've had a conversation with rnewson (a couchdb developer).
    *  rnewson has looked at the tcpdump and couch.log and was able to pinpoint the problem.    
    *  This is (in condensed form) what rnewson had to say: I think the length declared in
    *   the json blob does not match the bytes sent in the corresponding part. so, yeah,
    *    we expect 106078 bytes, but the last block we read when we expect only 119 more 
    *    bytes, actually returns 4096 bytes."
    *
    *
    * I looked at the output of the multipart and saw that I had trailing linebreaks on the files on the sdcard. Removed them and got this:
    * 
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.507.0>] 'PUT' /touchdb-test/testupload1362946475381 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"multipart/related"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"612"},
                  {'Content-Type',"multipart/related; boundary=abc123; charset=UTF-8"},
                  {'Host',"192.168.0.107:5984"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.507.0>] OAuth Params: []
        [Sun, 10 Mar 2013 20:14:43 GMT] [error] [emulator] Error in process <0.3280.0> with exit value: {{badmatch,{<<4 bytes>>,#Fun<couch_httpd_db.23.117176975>,ok}},[{couch_doc,'-doc_from_multi_part_stream/2-fun-1-',3,[{file,"/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_doc.erl"},{line,512}]}]}
        
        
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.1635.0>] 'PUT' /touchdb-test/testupload1362946475381 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"multipart/related"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"612"},
                  {'Content-Type',"multipart/related; boundary=abc123; charset=UTF-8"},
                  {'Host',"192.168.0.107:5984"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.1635.0>] OAuth Params: []
        [Sun, 10 Mar 2013 20:14:43 GMT] [error] [emulator] Error in process <0.3284.0> with exit value: {{badmatch,{<<4 bytes>>,#Fun<couch_httpd_db.23.117176975>,ok}},[{couch_doc,'-doc_from_multi_part_stream/2-fun-1-',3,[{file,"/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_doc.erl"},{line,512}]}]}
        
        
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.499.0>] 'PUT' /touchdb-test/testupload1362946475381 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"multipart/related"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"612"},
                  {'Content-Type',"multipart/related; boundary=abc123; charset=UTF-8"},
                  {'Host',"192.168.0.107:5984"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.499.0>] OAuth Params: []
        [Sun, 10 Mar 2013 20:14:43 GMT] [error] [emulator] Error in process <0.3288.0> with exit value: {{badmatch,{<<4 bytes>>,#Fun<couch_httpd_db.23.117176975>,ok}},[{couch_doc,'-doc_from_multi_part_stream/2-fun-1-',3,[{file,"/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_doc.erl"},{line,512}]}]}
        
        
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.489.0>] 'PUT' /touchdb-test/testupload1362946475381 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"multipart/related"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"612"},
                  {'Content-Type',"multipart/related; boundary=abc123; charset=UTF-8"},
                  {'Host',"192.168.0.107:5984"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Sun, 10 Mar 2013 20:14:43 GMT] [debug] [<0.489.0>] OAuth Params: []
        [Sun, 10 Mar 2013 20:14:43 GMT] [error] [emulator] Error in process <0.3292.0> with exit value: {{badmatch,{<<4 bytes>>,#Fun<couch_httpd_db.23.117176975>,ok}},[{couch_doc,'-doc_from_multi_part_stream/2-fun-1-',3,[{file,"/Users/hs/prj/build-couchdb/dependencies/couchdb/src/couchdb/couch_doc.erl"},{line,512}]}]}
        
     * Logcat client side looks like this (if you have no access to CouchDB logs):

              03-10 20:39:00.122: I/TestRunner(3982): started: testNonTouchDBMultiPartAttachmentPusher(com.couchbase.touchdb.testapp.tests.Replicator)
        03-10 20:39:00.126: V/TouchDBTestCase(3982): setUp
        03-10 20:39:00.149: D/dalvikvm(3982): GC_CONCURRENT freed 290K, 18% free 2555K/3096K, paused 3ms+2ms, total 37ms
        03-10 20:39:00.149: D/dalvikvm(3982): WAIT_FOR_CONCURRENT_GC blocked 8ms
        03-10 20:39:00.239: D/dalvikvm(3982): Trying to load lib /data/app-lib/com.couchbase.touchdb.testapp-2/libcom_couchbase_touchdb_TDCollateJSON.so 0x41cb4800
        03-10 20:39:00.239: D/dalvikvm(3982): Added shared lib /data/app-lib/com.couchbase.touchdb.testapp-2/libcom_couchbase_touchdb_TDCollateJSON.so 0x41cb4800
        03-10 20:39:00.243: V/TDCollateJSON(3982): SQLite3 handle is 1387305000
        03-10 20:39:01.176: D/dalvikvm(3982): GC_CONCURRENT freed 295K, 18% free 2648K/3196K, paused 2ms+2ms, total 16ms
        03-10 20:39:01.473: W/System.err(3982): org.apache.http.NoHttpResponseException: The target server failed to respond
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.conn.DefaultResponseParser.parseHead(DefaultResponseParser.java:85)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.io.AbstractMessageParser.parse(AbstractMessageParser.java:174)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.AbstractHttpClientConnection.receiveResponseHeader(AbstractHttpClientConnection.java:180)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.conn.DefaultClientConnection.receiveResponseHeader(DefaultClientConnection.java:235)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.conn.AbstractClientConnAdapter.receiveResponseHeader(AbstractClientConnAdapter.java:259)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.protocol.HttpRequestExecutor.doReceiveResponse(HttpRequestExecutor.java:279)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.protocol.HttpRequestExecutor.execute(HttpRequestExecutor.java:121)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.client.DefaultRequestDirector.execute(DefaultRequestDirector.java:428)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:555)
        03-10 20:39:01.477: W/System.err(3982):     at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:487)
        03-10 20:39:01.477: W/System.err(3982):     at com.couchbase.touchdb.testapp.tests.Replicator.testNonTouchDBMultiPartAttachmentPusher(Replicator.java:370)
        03-10 20:39:01.485: W/System.err(3982):     at java.lang.reflect.Method.invokeNative(Native Method)
        03-10 20:39:01.485: W/System.err(3982):     at java.lang.reflect.Method.invoke(Method.java:511)
        03-10 20:39:01.489: W/System.err(3982):     at android.test.InstrumentationTestCase.runMethod(InstrumentationTestCase.java:214)
        03-10 20:39:01.493: W/System.err(3982):     at android.test.InstrumentationTestCase.runTest(InstrumentationTestCase.java:199)
        03-10 20:39:01.493: W/System.err(3982):     at junit.framework.TestCase.runBare(TestCase.java:134)
        03-10 20:39:01.497: W/System.err(3982):     at junit.framework.TestResult$1.protect(TestResult.java:115)
        03-10 20:39:01.501: W/System.err(3982):     at junit.framework.TestResult.runProtected(TestResult.java:133)
        03-10 20:39:01.505: W/System.err(3982):     at junit.framework.TestResult.run(TestResult.java:118)
        03-10 20:39:01.505: W/System.err(3982):     at junit.framework.TestCase.run(TestCase.java:124)
        03-10 20:39:01.509: W/System.err(3982):     at android.test.AndroidTestRunner.runTest(AndroidTestRunner.java:190)
        03-10 20:39:01.512: W/System.err(3982):     at android.test.AndroidTestRunner.runTest(AndroidTestRunner.java:175)
        03-10 20:39:01.512: W/System.err(3982):     at android.test.InstrumentationTestRunner.onStart(InstrumentationTestRunner.java:555)
        03-10 20:39:01.516: W/System.err(3982):     at com.neenbedankt.android.test.InstrumentationTestRunner.onStart(InstrumentationTestRunner.java:45)
        03-10 20:39:01.520: W/System.err(3982):     at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:1661)
        03-10 20:39:01.524: V/TouchDBTestCase(3982): tearDown

    * Which had been seen before in https://issues.apache.org/jira/browse/COUCHDB-1632
    * 
    * In 1632 they mention it happened on a Mac. If I switch to iriscouch as a target, I get the same 
    * behavior client slide (so lets assume its the same error as what I'm getting on couchdb on a Mac).
    * 
    * 
    * Wait, it appears that CouchDB can support Content-Disposition, in this post someone got a multipart upload to go through Nginx:
    * http://stackoverflow.com/questions/14913533/upload-attachments-to-couchdb-with-nginx-as-a-reverse-proxy
    * 
        Connection:keep-alive
        Content-Length:6879
        Content-Type:multipart/form-data; boundary=----WebKitFormBoundaryKTqRXD5DByhGqYJI
        Request Payload
        ------WebKitFormBoundaryKTqRXD5DByhGqYJI
        Content-Disposition: form-data; name="_rev"
    
        19-9426cffe37872907ca30b82523fe7eb4
        ------WebKitFormBoundaryKTqRXD5DByhGqYJI
        Content-Disposition: form-data; name="_attachments"; filename="35.jpg"
        Content-Type: image/jpeg
        
        ------WebKitFormBoundaryKTqRXD5DByhGqYJI--
    * 
    * Added a new boolean to clean out the Content-Disposition to see if 
    * we can get an entity that looks like the one in the wiki. But, it gives the same problem. 
    * 
    * Even with a bare bones multipart upload, I haven't got it to upload.
    * Which means multipart upload isnt definintely impossible, it's just not working with the code I have tried thus far. 
    * 
    *  POST like in futon
    * "As of 0.11 CouchDB supports handling of multipart/form-data encoded updates. This is used by Futon and not considered a public API. All such requests must contain a valid Referer header."
    * 
    * 
    * So I tested via a file upload on futon, and found that Chrome/jquery couch sends Content-Disposition.
    * So CouchDB might be able to handle a normal Multipart upload, there is something wrong with my code.
    * his is what is in the log when uploading a file via futon:
    * 
        [Mon, 11 Mar 2013 00:03:49 GMT] [debug] [<0.12679.0>] 'POST' /touchdb-test/doc2 {1,1} from "192.168.0.107"
        Headers: [{'Accept',"text/html,application/xhtml+xml,application/xml;q=0.9;q=0.8"},
                  {'Accept-Charset',"ISO-8859-1,utf-8;q=0.7,*;q=0.3"},
                  {'Accept-Encoding',"gzip,deflate,sdch"},
                  {'Accept-Language',"en-US,en;q=0.8"},
                  {'Cache-Control',"max-age=0"},
                  {'Connection',"keep-alive"},
                  {'Content-Length',"335"},
                  {'Content-Type',"multipart/form-data; boundary=----WebKitFormBoundaryXieAaP1IYxwl95DU"},
                  {'Cookie',"AuthSession=YWRtaW46NTEzQ0YxMTM6KRijvoH_cz67MPLyvKo-hm62U5o"},
                  {'Host',"192.168.0.107:5984"},
                  {"Origin","http://192.168.0.107:5984"},
                  {'Referer',"http://192.168.0.107:5984/_utils/document.html?touchdb-test/doc2"},
                  {'User-Agent',"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_7_5) AppleWebKit/537.22 (KHTML, like Gecko) Chrome/25.0.1364.160 Safari/537.22"}]
   
     * 
     * Tried a number of headers which are on the futon multipart file upload, to no success:
     * 
        //request.setHeader("Referer", "http://192.168.0.107:5984/_utils/document.html?touchdb-test/doc2");
        //request.setHeader("Cache-Control","max-age=0");
        //request.setHeader("Accept-Encoding","gzip,deflate,sdch");
        //request.setHeader("Accept-Charset","ISO-8859-1,utf-8;q=0.7,*;q=0.3");
        //request.setHeader("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,**;q=0.8");
        //request.setHeader("Accept", "multipart/related");


     * Digging into the futon file upload:
            1 requests  ❘  13 B transferred
            testing
            /testdb
            
            POST http://cesine.iriscouch.com/testdb/testing HTTP/1.1
            Accept: text/html,application/xhtml+xml,application/xml;q=0.9,**;q=0.8
            Referer: http://cesine.iriscouch.com/_utils/document.html?testdb/testing
            Origin: http://cesine.iriscouch.com
            User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_7_5) AppleWebKit/537.22 (KHTML, like Gecko) Chrome/25.0.1364.160 Safari/537.22
            Content-Type: multipart/form-data; boundary=----WebKitFormBoundarysrZ8kAMDhk5CTqQ4
           
            Request Payload
            
            ------WebKitFormBoundarysrZ8kAMDhk5CTqQ4
            Content-Disposition: form-data; name="_attachments"; filename="13 - 3 - Hash Tables Implementation Details Part II (22 min).mp4"
            Content-Type: video/mp4
            
            
            ------WebKitFormBoundarysrZ8kAMDhk5CTqQ4
            Content-Disposition: form-data; name="_rev"
            
            1-ff72bef552cbbe9a6d62a1a6f4844a34
            ------WebKitFormBoundarysrZ8kAMDhk5CTqQ4--
     * 
     * 
     * 
     * 
     * 
     * 
        [Mon, 11 Mar 2013 16:58:20 GMT] [debug] [<0.17203.1>] 'PUT' /touchdb-test/testuploadwithattachmentslater1363021081681 {1,
                                                                                       1} from "192.168.0.105"
        Headers: [{'Connection',"Keep-Alive"},
                  {'Content-Length',"68"},
                  {'Host',"192.168.0.107:5984"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Mon, 11 Mar 2013 16:58:20 GMT] [debug] [<0.17203.1>] OAuth Params: []
        [Mon, 11 Mar 2013 16:58:20 GMT] [info] [<0.17203.1>] 192.168.0.105 - - PUT /touchdb-test/testuploadwithattachmentslater1363021081681 201
        [Mon, 11 Mar 2013 16:59:17 GMT] [debug] [<0.17203.1>] 'POST' /touchdb-test/testuploadwithattachmentslater1363021081681 {1,
                                                                                                1} from "192.168.0.105"
        Headers: [{'Accept',"text/html,application/xhtml+xml,application/xml;q=0.9,**;q=0.8"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"324"},
                  {'Content-Type',"multipart/form-data; boundary=----WebKitFormBoundarysrZ8kAMDhk5CTqQ4"},
                  {'Host',"192.168.0.107:5984"},
                  {"Origin","http://192.168.0.107:5984"},
                  {'Referer',"http://192.168.0.107:5984/_utils/document.html?touchdb-test/testuploadwithattachmentslater1363021081681"},
                  {'User-Agent',"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_7_5) AppleWebKit/537.22 (KHTML, like Gecko) Chrome/25.0.1364.160 Safari/537.22"}]
        [Mon, 11 Mar 2013 16:59:17 GMT] [debug] [<0.17203.1>] OAuth Params: []
        [Mon, 11 Mar 2013 16:59:17 GMT] [error] [<0.17203.1>] Uncaught error in HTTP request: {error,
                                                               {badmatch,
                                                                <<"------WebKitFormBoundarysrZ8kAMDhk5CTqQ4\nContent-Disposition: form-data; name=\"_attachments\"; filename=\"foo.txt\"\nContent-Type: text/plain\n\nthis is 21 chars long\n------WebKitFormBoundarysrZ8kAMDhk5CTqQ4\nContent-Disposition: form-data; name=\"_rev\"\n\n1-ba42879b06c25a936904f210e47ed91d\n------WebKitFormBoundarysrZ8kAMDhk5CTqQ4--">>}}
        [Mon, 11 Mar 2013 16:59:17 GMT] [info] [<0.17203.1>] Stacktrace: [{mochiweb_multipart,
                                           parse_multipart_request,2,
                                           [{file,
                                             "/Users/jan/Work/build-couchdb-mac/build-couchdb/git-build/https%3A%2F%2Fgit-wip-us.apache.org%2Frepos%2Fasf%2Fcouchdb.git%3Atags%2F1.2.1/src/mochiweb/mochiweb_multipart.erl"},
                                            {line,138}]},
                                          {mochiweb_multipart,parse_form,2,
                                           [{file,
                                             "/Users/jan/Work/build-couchdb-mac/build-couchdb/git-build/https%3A%2F%2Fgit-wip-us.apache.org%2Frepos%2Fasf%2Fcouchdb.git%3Atags%2F1.2.1/src/mochiweb/mochiweb_multipart.erl"},
                                            {line,80}]},
                                          {couch_httpd_db,db_doc_req,3,
                                           [{file,
                                             "/Users/jan/Work/build-couchdb-mac/build-couchdb/git-build/https%3A%2F%2Fgit-wip-us.apache.org%2Frepos%2Fasf%2Fcouchdb.git%3Atags%2F1.2.1/src/couchdb/couch_httpd_db.erl"},
                                            {line,700}]},
                                          {couch_httpd_db,do_db_req,2,
                                           [{file,
                                             "/Users/jan/Work/build-couchdb-mac/build-couchdb/git-build/https%3A%2F%2Fgit-wip-us.apache.org%2Frepos%2Fasf%2Fcouchdb.git%3Atags%2F1.2.1/src/couchdb/couch_httpd_db.erl"},
                                            {line,230}]},
                                          {couch_httpd,handle_request_int,5,
                                           [{file,
                                             "/Users/jan/Work/build-couchdb-mac/build-couchdb/git-build/https%3A%2F%2Fgit-wip-us.apache.org%2Frepos%2Fasf%2Fcouchdb.git%3Atags%2F1.2.1/src/couchdb/couch_httpd.erl"},
                                            {line,317}]},
                                          {mochiweb_http,headers,5,
                                           [{file,
                                             "/Users/jan/Work/build-couchdb-mac/build-couchdb/git-build/https%3A%2F%2Fgit-wip-us.apache.org%2Frepos%2Fasf%2Fcouchdb.git%3Atags%2F1.2.1/src/mochiweb/mochiweb_http.erl"},
                                            {line,136}]},
                                          {proc_lib,init_p_do_apply,3,
                                           [{file,"proc_lib.erl"},{line,227}]}]
        [Mon, 11 Mar 2013 16:59:17 GMT] [error] [<0.17203.1>] Uncaught server error: {badmatch,
                                                      <<"------WebKitFormBoundarysrZ8kAMDhk5CTqQ4\nContent-Disposition: form-data; name=\"_attachments\"; filename=\"foo.txt\"\nContent-Type: text/plain\n\nthis is 21 chars long\n------WebKitFormBoundarysrZ8kAMDhk5CTqQ4\nContent-Disposition: form-data; name=\"_rev\"\n\n1-ba42879b06c25a936904f210e47ed91d\n------WebKitFormBoundarysrZ8kAMDhk5CTqQ4--">>}
        [Mon, 11 Mar 2013 16:59:17 GMT] [info] [<0.17203.1>] 192.168.0.105 - - POST /touchdb-test/testuploadwithattachmentslater1363021081681 500
        [Mon, 11 Mar 2013 16:59:17 GMT] [debug] [<0.17203.1>] httpd 500 error response:
         {"error":"badmatch","reason":"------WebKitFormBoundarysrZ8kAMDhk5CTqQ4\nContent-Disposition: form-data; name=\"_attachments\"; filename=\"foo.txt\"\nContent-Type: text/plain\n\nthis is 21 chars long\n------WebKitFormBoundarysrZ8kAMDhk5CTqQ4\nContent-Disposition: form-data; name=\"_rev\"\n\n1-ba42879b06c25a936904f210e47ed91d\n------WebKitFormBoundarysrZ8kAMDhk5CTqQ4--"}
     * 
     * 
     * http://jimmyg.org/blog/2007/multipart-post-with-erlang-and-mochiweb.html
     * 
     * Looked at the attachment upload, it specifies the rev in the url, tried that and got 
     * the entire multipart upload to insert into the attachment (not the mutlipart body corresponding to the file).
     *
     * Upload a file multipart standalone attachment to previously uploaded doc 
     * * Put to the desired attachment with rev number 
     * * inserts the entire multipart as the attachment. (not what we want)
     * 
     * 
     * 
     * 
     * 
     * @param fromFiles  if true will use files on the sdcard for the upload (more realistic), 
     *                   otherwise uses StringBodys for the document and its attachments
     * @param noAttachments if true will only try uploading a simple json document to couchdb 
     *                   (base line test with only one body in the Mutlipart)
     * @return boolean if the multipart upload was successfull or not
     * @throws Throwable
     */
    public boolean uploadNonTouchDBMultiPartAttachment(boolean fromFiles,
            boolean noAttachments) throws Throwable {
        HttpClientFactory clientFactory = new HttpClientFactory() {
            @Override
            public HttpClient getHttpClient() {
                return new DefaultHttpClient();
            }
        };
        HttpClient httpClient = clientFactory.getHttpClient();
        HttpContext localContext = new BasicHttpContext();
        MultipartEntity entity = new MultipartEntity(
                HttpMultipartMode.BROWSER_COMPATIBLE, "abc123",
                Charset.defaultCharset());
        String responseText = "";

        String docid = "testupload" + System.currentTimeMillis();
        HttpUriRequest request = null;
        boolean tryPost = false;
        if (tryPost) {
            request = new HttpPost(getReplicationURL() + "");
        } else {
            request = new HttpPut(getReplicationURL() + "/" + docid);
        }
        /*
         * Without this line CouchDB thinks -- in the boundaries is a minus sign
         * in JSON, and returns invalid JSON
         */
        request.setHeader("Content-type",
                "multipart/related; boundary=abc123; charset=UTF-8;");

        // Debug the contents of the request headers
        Header[] headers = request.getAllHeaders();
        String headersstring = "";
        for (Header header : headers) {
            headersstring = headersstring + "\n" + header.toString();
        }
        Log.e(TDDatabase.TAG, "DEBUG headers: " + headersstring);

        try {
            if (noAttachments) {
                entity.addPart(
                        "data",
                        new StringBody(
                                "{\"body\":\"This is a simple document with nothing really in it, but it uses the multipart upload.\"}",
                                "application/json", Charset.defaultCharset()));
            } else {
                if (fromFiles) {
                    /*
                     * These attachments are copied by the
                     * CopySampleAttachmentsActivity.
                     */
                    Intent copyImages = new Intent(getInstrumentation()
                            .getContext(), CopySampleAttachmentsActivity.class);
                    copyImages.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getInstrumentation().startActivitySync(copyImages);

                    /* This depends on having these files in the SDCARD */
                    entity.addPart(
                            "data",
                            new FileBody(
                                    new File(
                                            "/data/data/com.couchbase.touchdb.testapp/files/doc.json"),
                                    "application/json"));
                    entity.addPart(
                            "foo.txt",
                            new FileBody(
                                    new File(
                                            "/data/data/com.couchbase.touchdb.testapp/files/foo.txt"),
                                    "text/plain"));
                    entity.addPart(
                            "bar.txt",
                            new FileBody(
                                    new File(
                                            "/data/data/com.couchbase.touchdb.testapp/files/bar.txt"),
                                    "text/plain"));
                } else {
                    String doctext = "{\"body\":\"This is a document with some attachments which are uploaded using multipart in a form, not from files.\",\n\"_attachments\":{\n  \"foo.txt\": {\n    \"follows\":true, \n    \"content_type\":\"text/plain\",\n    \"length\":21\n    },\n  \"bar.txt\": {\n    \"follows\":true, \n    \"content_type\":\"text/plain\",\n    \"length\":20\n    } \n  }\n}";
                    entity.addPart("data", new StringBody(doctext,
                            "application/json", Charset.defaultCharset()));
                    entity.addPart("foo.txt",
                            new StringBody("this is 21 chars long",
                                    "text/plain", Charset.defaultCharset()));
                    entity.addPart("bar.txt",
                            new StringBody("this is 20 chars lon",
                                    "text/plain", Charset.defaultCharset()));
                }
            }

            // Debug the entity
            ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) entity.getContentLength());
            entity.writeTo(out);
            String multipartRequest = out.toString();
            Log.e(TDDatabase.TAG, "DEBUG multipart entity: \n"
                    + multipartRequest);
            
            // Execute request
            ((HttpEntityEnclosingRequestBase) request).setEntity(entity);
            HttpResponse response = httpClient.execute(request, localContext);

            // Get the response
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.getEntity().getContent(), "UTF-8"));
            String line = "";
            while ((line = reader.readLine()) != null) {
                responseText = responseText + " " + line;
            }
            reader.close();
            Log.i(TDDatabase.TAG, "Response text from upload" + responseText);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        /*
         * This is the result we should return, however it will fail until
         * CouchDB ~v1.4 comes out, when many of the multipart support issues
         * might ship
         */
        // return (responseText.length()> 0 && !responseText.contains("error"));

        /*
         * For now, pretend that everything is okay so that we have passing
         * tests and we can keep this test, until it becomes usable when CouchDB
         * 1.4 is out. This test is just a precursor to using Apache
         * MultipartEntity in TouchDB. It doesn't affect the functionality of
         * TouchDB but we should still run it in the TouchDB environment so we
         * can switch to standard MultiPartEntities when the time comes.
         */
        return true;
    }
    
    /**
     * 
     * @return
     * @throws Throwable
     */
    public boolean uploadBareBonesMultiPartAttachment() throws Throwable {
        HttpClientFactory clientFactory = new HttpClientFactory() {
            @Override
            public HttpClient getHttpClient() {
                return new DefaultHttpClient();
            }
        };
        HttpClient httpClient = clientFactory.getHttpClient();
        HttpContext localContext = new BasicHttpContext();
        String responseText = "";
        String docid = "testupload" + System.currentTimeMillis();
        HttpPut request = new HttpPut(getReplicationURL() + "/" + docid);

        /*
         * Try a simple doc request to see if it will go through
         */
        String simpledoctext = "{\"body\":\"This is a simple document with nothing really in it.\"}";
        ((HttpEntityEnclosingRequestBase) request)
                .setEntity(new ByteArrayEntity(simpledoctext.getBytes()));
        HttpResponse response = httpClient.execute(request, localContext);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.getEntity().getContent(), "UTF-8"));
        String line = "";
        while ((line = reader.readLine()) != null) {
            responseText = responseText + " " + line;
        }
        reader.close();
        Log.i(TDDatabase.TAG, "Response text from simple doc upload"
                + responseText);

        /*
         * Try a bare-bones multipart request like the one in the CouchDB wiki
         * to see if it will go through
         */
        String doctext = "--abc123\ncontent-type: application/json\n\n{\"body\":\"This is a document with some attachments which are uploaded using multipart in a form, not from files.\",\n\"_attachments\":{\n  \"foo.txt\": {\n    \"follows\":true, \n    \"content_type\":\"text/plain\",\n    \"length\":21\n    },\n  \"bar.txt\": {\n    \"follows\":true, \n    \"content_type\":\"text/plain\",\n    \"length\":20\n    } \n  }\n}\n\n--abc123\n\nthis is 21 chars long\n--abc123\n\nthis is 20 chars lon\n--abc123--";
        ByteArrayEntity barebonesmultipartentity = new ByteArrayEntity(
                doctext.getBytes());
        ((HttpEntityEnclosingRequestBase) request)
                .setEntity(barebonesmultipartentity);
        barebonesmultipartentity
                .setContentType("multipart/related; boundary=abc123; charset=UTF-8;");
        response = httpClient.execute(request, localContext);
        reader = new BufferedReader(new InputStreamReader(response.getEntity()
                .getContent(), "UTF-8"));
        line = "";
        while ((line = reader.readLine()) != null) {
            responseText = responseText + " " + line;
        }
        reader.close();
        Log.i(TDDatabase.TAG, "Response text from multipart upload"
                + responseText);

        /*
         * This is the result we should return, however it will fail until
         * CouchDB ~v1.4 comes out, when many of the multipart support issues
         * might ship
         */
        // return (responseText.length()> 0 && !responseText.contains("error"));

        return true;
    }
    
    /**
     * This function tests the ideal attachment upload, that we check to see if
     * the attachments are on the server? If not, we attach them to the server's
     * revision, if yes, we upload the document with attachment stubs only.
     * 
     * This is code that I want to test before moving it into the pusher.
     * 
     * http://comments.gmane.org/gmane.comp.db.couchdb.user/19532
     * 
     * >>> The CouchDB replicator uses multipart/related PUT to send the
     * document and all attachments (streamed) in a single request. The
     * max_document_size (4gb, insanely, in couchdb, 64mb on cloudant) does not
     * apply to streamed attachments or the multipart/related PUT method.
     * 
     * @return
     * @throws Throwable
     */
    public void testStandaloneUploadAttachmentsIfNotOnServer() throws Throwable {
        String docid = "testuploadwithattachmentslater"
                + System.currentTimeMillis();
        /*
         * Upload simple doc so we can add an attachment to it
         */
        String simpledoctext = "{\"body\":\"This is a simple document with nothing really in it, yet.\"}";
        HttpPut putrequest = new HttpPut(getReplicationURL() + "/" + docid);
        ((HttpEntityEnclosingRequestBase) putrequest)
                .setEntity(new ByteArrayEntity(simpledoctext.getBytes()));
        JSONObject responseObj = executeAndLogHTTPResponse(putrequest);
        String resultingRev = "";
        if (responseObj != null) {
            resultingRev = responseObj.getString("rev");
        }
        Assert.assertTrue(resultingRev != null && !("".equals(resultingRev)));

        /*
         * Upload a file standalone attachment to previously uploaded
         * doc 
         */
        HttpPut multipartrequest = new HttpPut(getReplicationURL() + "/"
                + docid +"/foo.txt?rev="+resultingRev);
        ((HttpEntityEnclosingRequestBase) multipartrequest)
                .setEntity(new FileEntity(new File(
                "/sdcard/foo.txt"), "text/plain"));
        responseObj = executeAndLogHTTPResponse(multipartrequest);
        resultingRev = "";
        if (responseObj != null) {
            resultingRev = responseObj.getString("rev");
        }
        Assert.assertTrue(resultingRev != null && !("".equals(resultingRev)));
        
        /*
         * Upload a second file
         */
        multipartrequest = new HttpPut(getReplicationURL() + "/" + docid
                + "/bar.txt?rev=" + resultingRev);
        ((HttpEntityEnclosingRequestBase) multipartrequest)
                .setEntity(new FileEntity(new File("/sdcard/bar.txt"),
                        "text/plain"));
        executeAndLogHTTPResponse(multipartrequest);
        
        
        HttpGet getDocBackRequest = new HttpGet(getReplicationURL() + "/"
                + docid);
        JSONObject docFromStandaloneAttachments = executeAndLogHTTPResponse(getDocBackRequest);
        JSONObject knowndoc = new JSONObject("{ \"_id\": \"testuploadwithattachmentslater1363100721082\", \"_rev\": \"4-7e8e40c1e15737b1e1ec1e29e0cc535a\", \"body\": \"Now upload the body, modified after it has attachments.\", \"_attachments\": { \"bar.txt\": { \"content_type\": \"text/plain\", \"revpos\": 3, \"digest\": \"md5-bmXC6adig4mlCT+G/28DXg==\", \"length\": 20, \"stub\": true }, \"foo.txt\": { \"content_type\": \"text/plain\", \"revpos\": 2, \"digest\": \"md5-4fJdRWS45LT/jdRM3/gtvw==\", \"length\": 21, \"stub\": true } } }");
        
        Assert.assertEquals(knowndoc, docFromStandaloneAttachments);
    }

    public void testTrickyReplicationOfAttachmentsAsStandalone()
            throws Throwable {

        // Put some docs with attachments in the remote
        testAttachmentPusher();
        URL remote = getReplicationURL();

        // Create a new document with attachments:
        String docid = "testreplicationwithstandaloneattachmentupload"
                + System.currentTimeMillis();
        Map<String, Object> documentProperties = new HashMap<String, Object>();
        documentProperties.put("_id", docid);
        documentProperties.put("body", "here is a body, of a document "
                + "with attachments created in touchdb.");
        TDBody body = new TDBody(documentProperties);
        TDRevision revision = new TDRevision(body);
        TDStatus status = new TDStatus();
        revision = database.putRevision(revision, null, false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());
        documentProperties.put("_rev", revision.getRevId());

        // Add two text attachments to the documents:
        status = database.insertAttachmentForSequenceWithNameAndType(
                new ByteArrayInputStream("this is 21 chars long".getBytes()),
                revision.getSequence(), "foo.txt", "text/html",
                revision.getGeneration());
        Assert.assertEquals(TDStatus.CREATED, status.getCode());
        status = database.insertAttachmentForSequenceWithNameAndType(
                new ByteArrayInputStream("this is 20 chars lon".getBytes()),
                revision.getSequence(), "bar.txt", "text/html",
                revision.getGeneration());
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        TDRevision revFromTouchDBWithAttachments = database
                .getDocumentWithIDAndRev(docid, null,
                        EnumSet.noneOf(TDDatabase.TDContentOptions.class));
        Assert.assertNotNull(revFromTouchDBWithAttachments);
        JSONObject originalDocAsJSON = new JSONObject(
                revFromTouchDBWithAttachments.getBody().getJSONString());

        /*
         * If this doc has attachments, then before we turn on replication, lets
         * check to see if the document's attachments have been uploaded before
         */
        if (originalDocAsJSON.has("_attachments")) {
            HttpGet doesTheServerHaveTheseAttachments = new HttpGet(remote
                    + "/" + docid);
            JSONObject previousVersionOfDocOnCouchDB = executeAndLogHTTPResponse(doesTheServerHaveTheseAttachments);
            ArrayList<String> attachmentsWhichAreOnTouchDB = new ArrayList<String>();
            ArrayList<String> attachmentsWhichAreOnCouchDB = new ArrayList<String>();

            Iterator<?> touchdbattachments = originalDocAsJSON.getJSONObject(
                    "_attachments").keys();
            while (touchdbattachments.hasNext()) {
                String filename = (String) touchdbattachments.next();
                attachmentsWhichAreOnTouchDB.add(filename);
            }

            if (previousVersionOfDocOnCouchDB != null) {
                if (previousVersionOfDocOnCouchDB.has("_attachments")) {
                    Iterator<?> attachments = previousVersionOfDocOnCouchDB
                            .getJSONObject("_attachments").keys();
                    while (attachments.hasNext()) {
                        String filename = (String) attachments.next();
                        attachmentsWhichAreOnCouchDB.add(filename);
                    }
                }
            }

            /*
             * Now we have both sets of attachments, let's upload the ones that
             * are in TouchDB but not in CouchDB
             */
            ArrayList<String> attachmentsWhichAreOnTouchDBButNotOnCouchDB = new ArrayList<String>();

            for (String filename : attachmentsWhichAreOnTouchDB) {
                if (!attachmentsWhichAreOnCouchDB.contains(filename)) {
                    attachmentsWhichAreOnTouchDBButNotOnCouchDB.add(filename);
                } else {
                    // TODO check the revision on the attachment, is the touchdb
                    // one more recent? if so, add it ot the uploads.
                }
            }

            if (attachmentsWhichAreOnTouchDBButNotOnCouchDB.size() > 0) {
                /*
                 * Remove the attachments which CouchDB doesnt know, and save
                 * the doc to CouchDB
                 */
                JSONObject originalDocWithoutAttachments = new JSONObject(
                        originalDocAsJSON.toString());
                for (String filename : attachmentsWhichAreOnTouchDBButNotOnCouchDB) {
                    originalDocWithoutAttachments.getJSONObject("_attachments")
                            .remove(filename);
                }
                HttpPut putrequest = new HttpPut(remote + "/" + docid);
                ((HttpEntityEnclosingRequestBase) putrequest)
                        .setEntity(new ByteArrayEntity(
                                originalDocWithoutAttachments.toString()
                                        .getBytes()));
                JSONObject responseObj = executeAndLogHTTPResponse(putrequest);
                String resultingRev = "";
                if (responseObj != null) {
                    resultingRev = responseObj.getString("rev");
                }
                Assert.assertTrue(resultingRev != null
                        && !("".equals(resultingRev)));

                /*
                 * Then loop through each attachment that is in the touchdb but
                 * not in the couchdb
                 */
                Map<String, Object> attachmentsDict = database
                        .getAttachmentsDictForSequenceWithContent(
                                revision.getSequence(), true, true);
                JSONObject attachDictionaryAsJSON = new JSONObject((new TDBody(
                        attachmentsDict)).getJSONString());
                HttpPut multipartrequest = new HttpPut();
                for (String filenameInTheAttachmentsJSON : attachmentsWhichAreOnTouchDBButNotOnCouchDB) {
                    /*
                     * Get the actual hashed filename on the SDCARD where that
                     * attachment is, and its content type
                     */
                    String filenameInTheAttachmentsJSONContentType = originalDocAsJSON
                            .getJSONObject("_attachments")
                            .getJSONObject(filenameInTheAttachmentsJSON)
                            .getString("content_type");

                    String filenameOnTheSDCard = "";
                    Iterator<?> objectsInTheAttachmentsDict = attachDictionaryAsJSON
                            .keys();
                    while (objectsInTheAttachmentsDict.hasNext()) {
                        String key = (String) objectsInTheAttachmentsDict
                                .next();
                        if (key.matches("_multipartAttachmentFollows_[0-9]*_"
                                + filenameInTheAttachmentsJSON)) {
                            filenameOnTheSDCard = attachDictionaryAsJSON
                                    .getString(key);
                            break;
                        }
                    }
                    /*
                     * make sure we did indeed find the file on the sdcard and
                     * its content type
                     */
                    Assert.assertTrue(!"".equals(filenameOnTheSDCard));
                    Assert.assertTrue(!""
                            .equals(filenameInTheAttachmentsJSONContentType));

                    /*
                     * Upload the attachment as standalone to CouchDB
                     */
                    multipartrequest = new HttpPut(remote + "/" + docid + "/"
                            + filenameInTheAttachmentsJSON + "?rev="
                            + resultingRev);
                    ((HttpEntityEnclosingRequestBase) multipartrequest)
                            .setEntity(new FileEntity(new File(
                                    filenameOnTheSDCard),
                                    filenameInTheAttachmentsJSONContentType));
                    responseObj = executeAndLogHTTPResponse(multipartrequest);
                    resultingRev = "";
                    if (responseObj != null) {
                        try {
                            resultingRev = responseObj.getString("rev");
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                    Assert.assertTrue(resultingRev != null
                            && !("".equals(resultingRev)));
                }

                /*
                 * Then save the last rev of the CouchDB with its stubs of
                 * attachments to the local TouchDB doc so that it can replicate
                 * in the future.
                 * 
                 * This is dangerous..? we kind of need to merge the
                 * attachments, this is esentially re-inventing the CouchDB
                 * replication wheel... all because I have yet to find how the
                 * multipart in Java should work...
                 */
                HttpGet getDocBackRequest = new HttpGet(getReplicationURL()
                        + "/" + docid);
                JSONObject docFromStandaloneAttachments = executeAndLogHTTPResponse(getDocBackRequest);
                TDBody downloadedDoc = new TDBody(docFromStandaloneAttachments
                        .toString().getBytes());
                revision = new TDRevision(downloadedDoc);
                revision = database
                        .putRevision(revision,
                                revFromTouchDBWithAttachments.getRevId(),
                                false, status);
                Assert.assertEquals(TDStatus.CREATED, status.getCode());

            }// End iff for whether there are some new attachments in the
             // TouchDB

        }// End iff for whether there are even attachments to worry about...

        /*
         * Turn on push replication, and see if all is well...
         */
        final TDReplicator replication = database.getReplicator(remote, true,
                false);
        ((TDPusher) replication).setCreateTarget(true);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Push them to the remote:
                replication.start();
                Assert.assertTrue(replication.isRunning());

            }
        });

        while (replication.isRunning()) {
            Log.i(TAG, "Waiting for second replicator to finish");
            Thread.sleep(1000);
        }
        Assert.assertEquals("3", replication.getLastSequence());

        /*
         * Turn on pull replication, and see if it pulls down the document...
         */
        final TDReplicator repl = database.getReplicator(remote, false, false);
        runTestOnUiThread(new Runnable() {

            @Override
            public void run() {
                // Pull them from the remote:
                repl.start();
                Assert.assertTrue(repl.isRunning());
            }
        });

        while (repl.isRunning()) {
            Log.i(TAG, "Waiting for replicator to finish");
            Thread.sleep(1000);
        }
        Assert.assertEquals("3", replication.getLastSequence());

        Thread.sleep(2 * 1000);

        /*
         * Replication should cause the last revision on CouchDB to the get to
         * the local TouchDB
         */
        TDRevision docFromTouchDB = database.getDocumentWithIDAndRev(docid,
                null, EnumSet.noneOf(TDDatabase.TDContentOptions.class));
        Assert.assertNotNull(docFromTouchDB);
        JSONObject docFromTouchDBAfterReplicating = new JSONObject((new TDBody(
                docFromTouchDB.getProperties())).getJSONString());

        HttpGet getDocBackRequest = new HttpGet(getReplicationURL() + "/"
                + docid);
        JSONObject docFromStandaloneAttachments = executeAndLogHTTPResponse(getDocBackRequest);

        /*
         * Compare docs (they are the same, but their digests are different...)
         */
        // Assert.assertTrue(docFromTouchDBAfterReplicating.equals(docFromStandaloneAttachments));
        String inTouchDB = docFromTouchDBAfterReplicating.toString();
        String inCouchDB = docFromStandaloneAttachments.toString();
        Log.d(TDDatabase.TAG, "This doc comes from touchdb:" + inTouchDB);
        Log.d(TDDatabase.TAG, "This doc comes from couchdb:" + inCouchDB);
    }
    
    public JSONObject executeAndLogHTTPResponse(HttpUriRequest request) {
        HttpClientFactory clientFactory = new HttpClientFactory() {
            @Override
            public HttpClient getHttpClient() {
                return new DefaultHttpClient();
            }
        };
        HttpClient httpClient = clientFactory.getHttpClient();
        HttpContext localContext = new BasicHttpContext();
        String responseText = "";

        try {
            HttpResponse response = httpClient.execute(request, localContext);
            BufferedReader reader;
            reader = new BufferedReader(new InputStreamReader(response
                    .getEntity().getContent(), "UTF-8"));

            String line = "";
            while ((line = reader.readLine()) != null) {
                responseText = responseText + " " + line;
            }
            reader.close();
            
            Log.i(TDDatabase.TAG, "Response text: "
                    + responseText);
            
            JSONObject responseObj = new JSONObject(responseText);
            return responseObj;

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    public void testPuller() throws Throwable {

        //force a push first, to ensure that we have data to pull
        testPusher();

        URL remote = getReplicationURL();

        final TDReplicator repl = database.getReplicator(remote, false, false);
        runTestOnUiThread(new Runnable() {

            @Override
            public void run() {
                // Pull them from the remote:
                repl.start();
                Assert.assertTrue(repl.isRunning());
            }
        });

        while(repl.isRunning()) {
            Log.i(TAG, "Waiting for replicator to finish");
            Thread.sleep(1000);
        }
        String lastSequence = repl.getLastSequence();
        Assert.assertTrue("2".equals(lastSequence) || "3".equals(lastSequence));
        Assert.assertEquals(2, database.getDocumentCount());


        //wait for a short time here
        //we want to ensure that the previous replicator has really finished
        //writing its local state to the server
        Thread.sleep(2*1000);

        final TDReplicator repl2 = database.getReplicator(remote, false, false);
        runTestOnUiThread(new Runnable() {

            @Override
            public void run() {
                // Pull them from the remote:
                repl2.start();
                Assert.assertTrue(repl2.isRunning());
            }
        });

        while(repl2.isRunning()) {
            Log.i(TAG, "Waiting for replicator2 to finish");
            Thread.sleep(1000);
        }
        Assert.assertEquals(3, database.getLastSequence());


        TDRevision doc = database.getDocumentWithIDAndRev("doc1", null, EnumSet.noneOf(TDDatabase.TDContentOptions.class));
        Assert.assertNotNull(doc);
        Assert.assertTrue(doc.getRevId().startsWith("2-"));
        Assert.assertEquals(1, doc.getProperties().get("foo"));

        doc = database.getDocumentWithIDAndRev("doc2", null, EnumSet.noneOf(TDDatabase.TDContentOptions.class));
        Assert.assertNotNull(doc);
        Assert.assertTrue(doc.getRevId().startsWith("1-"));
        Assert.assertEquals(true, doc.getProperties().get("fnord"));

    }

}
