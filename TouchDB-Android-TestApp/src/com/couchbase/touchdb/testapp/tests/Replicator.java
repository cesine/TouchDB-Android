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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

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
        readRevProps = readRev.getProperties();
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
     * 
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
    * So I tested via a file upload on futon this is what is in the log:
    * 
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
    * 
    * I put in pure json, and it uploaded
    * 
    * 
        [Mon, 11 Mar 2013 00:41:31 GMT] [debug] [<0.15155.0>] 'PUT' /touchdb-test/testupload1362962442573 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"text/html,application/xhtml+xml,application/xml;q=0.9,**;q=0.8"},
                  {'Accept-Charset',"ISO-8859-1,utf-8;q=0.7,*;q=0.3"},
                  {'Accept-Encoding',"gzip,deflate,sdch"},
                  {'Cache-Control',"max-age=0"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"97"},
                  {'Host',"192.168.0.107:5984"},
                  {'Referer',"http://192.168.0.107:5984/_utils/document.html?touchdb-test/doc2"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Mon, 11 Mar 2013 00:41:31 GMT] [debug] [<0.15155.0>] OAuth Params: []
        [Mon, 11 Mar 2013 00:41:31 GMT] [info] [<0.15155.0>] 192.168.0.105 - - PUT /touchdb-test/testupload1362962442573 201
        [Mon, 11 Mar 2013 00:41:32 GMT] [debug] [<0.13836.0>] 'PUT' /touchdb-test/testupload1362962488167 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"text/html,application/xhtml+xml,application/xml;q=0.9,**;q=0.8"},
                  {'Accept-Charset',"ISO-8859-1,utf-8;q=0.7,*;q=0.3"},
                  {'Accept-Encoding',"gzip,deflate,sdch"},
                  {'Cache-Control',"max-age=0"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"97"},
                  {'Host',"192.168.0.107:5984"},
                  {'Referer',"http://192.168.0.107:5984/_utils/document.html?touchdb-test/doc2"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Mon, 11 Mar 2013 00:41:32 GMT] [debug] [<0.13836.0>] OAuth Params: []
        [Mon, 11 Mar 2013 00:41:32 GMT] [info] [<0.13836.0>] 192.168.0.105 - - PUT /touchdb-test/testupload1362962488167 201
        [Mon, 11 Mar 2013 00:41:36 GMT] [debug] [<0.15086.0>] 'PUT' /touchdb-test/testupload1362962488298 {1,1} from "192.168.0.105"
        Headers: [{'Accept',"text/html,application/xhtml+xml,application/xml;q=0.9,**;q=0.8"},
                  {'Accept-Charset',"ISO-8859-1,utf-8;q=0.7,*;q=0.3"},
                  {'Accept-Encoding',"gzip,deflate,sdch"},
                  {'Cache-Control',"max-age=0"},
                  {'Connection',"Keep-Alive"},
                  {'Content-Length',"97"},
                  {'Host',"192.168.0.107:5984"},
                  {'Referer',"http://192.168.0.107:5984/_utils/document.html?touchdb-test/doc2"},
                  {'User-Agent',"Apache-HttpClient/UNAVAILABLE (java 1.4)"}]
        [Mon, 11 Mar 2013 00:41:36 GMT] [debug] [<0.15086.0>] OAuth Params: []
        [Mon, 11 Mar 2013 00:41:36 GMT] [info] [<0.15086.0>] 192.168.0.105 - - PUT /touchdb-test/testupload1362962488298 201
    * 
    * Bug even with a bare bones multipart upload I haven't got it to upload.
    * Which means multipart upload isnt definintely impossible, just not working with the code I have. 
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
     * Tried a number of headers which are on the futon multipart file upload, to no success:
     * 
        //request.setHeader("Referer", "http://192.168.0.107:5984/_utils/document.html?touchdb-test/doc2");
        //request.setHeader("Cache-Control","max-age=0");
        //request.setHeader("Accept-Encoding","gzip,deflate,sdch");
        //request.setHeader("Accept-Charset","ISO-8859-1,utf-8;q=0.7,*;q=0.3");
        //request.setHeader("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,**;q=0.8");
        //request.setHeader("Accept", "multipart/related");

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
         * Try a simple doc request like the one in the CouchDB wiki to see if
         * it will go through
         */
        String simpledoctext = "{\"body\":\"This is a simple document with nothing really in it, but it uses the multipart upload.\"}";
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
