package com.couchbase.touchdb.replicator;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import junit.framework.Assert;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.HttpMultipart;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.codehaus.jackson.map.ObjectMapper;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;

import com.couchbase.touchdb.TDBody;
import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDRevision;
import com.couchbase.touchdb.TDStatus;
import com.couchbase.touchdb.support.HttpClientFactory;

//https://code.google.com/p/gdata-java-client/source/browse/trunk/java/src/com/google/gdata/data/media/MediaMultipart.java
public class MultiPartWriter extends ObjectMapper {

    public static void buildMultipart(Object doc) {
        JSONObject docFromTouchDBAfterReplicating = new JSONObject(doc);
        JSONObject originalDocAsJSON = new JSONObject();
        /*
         * If this doc has attachments, then before we turn on replication, lets
         * check to see if the document's attachments have been uploaded before
         * 
         * https://github.com/couchbaselabs/TouchDB-iOS/blob/master/Source/TDPusher
         * .m
         * 
         * // Strip any attachments already known to the target db: if
         * (properties[@"_attachments"]) { // Look for the latest common
         * ancestor and stub out older attachments: NSArray* possible =
         * revResults[@"possible_ancestors"]; int minRevPos =
         * findCommonAncestor(rev, possible); [TD_Database stubOutAttachmentsIn:
         * rev beforeRevPos: minRevPos + 1 attachmentsFollow: NO]; properties =
         * rev.properties; // If the rev has huge attachments, send it under
         * separate cover: if (!_dontSendMultipart && [self
         * uploadMultipartRevision: rev]) return nil; }
         */
        String remote = "";
        String docid = originalDocAsJSON.getString("_id");
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
                Map<String, Object> attachmentsDict = doc;
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

    }

    /**
     * Use HttpMultipart to create a multipart/related entity (not
     * multipart/form-data) to upload to a couchdb
     * 
     * @param url
     *            url to upload the json
     * @param jsonString
     *            json to upload
     * @throws IOException
     * @throws JSONException
     */
    public static void runMultipartRelatedUpload(String url,
            JSONObject jsonString) throws IOException, JSONException {

        buildMultipart(jsonString);

        HttpMultipart multipart = new HttpMultipart("related",
                Charset.defaultCharset(), "abc123");
        multipart.addBodyPart(new FormBodyPart("json", new StringBody(
                jsonString, "application/json", Charset.defaultCharset())));
        multipart.addBodyPart(new FormBodyPart("bar.txt", new FileBody(
                new File("/sdcard/bar.txt"), "bar.txt", "text/plain")));
        multipart.addBodyPart(new FormBodyPart("foo.txt", new FileBody(
                new File("/sdcard/foo.txt"), "foo.txt", "text/plain")));
        debugMultipart(multipart);

        URL uploadURL = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) uploadURL
                .openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Content-type",
                " multipart/" + multipart.getSubType() + "; boundary="
                        + multipart.getBoundary() + "; charset="
                        + multipart.getCharset().displayName());
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection
                .setFixedLengthStreamingMode((int) multipart.getTotalLength());
        DataOutputStream out = new DataOutputStream(
                connection.getOutputStream());
        multipart.writeTo(out);
        out.flush();
        out.close();
        connection.disconnect();
        Log.d(TDDatabase.TAG, "Finished uploading to the connection: ");
    }

    public static void debugMultipart(HttpMultipart multipart)
            throws IOException {
        ByteArrayOutputStream debugEntity = new ByteArrayOutputStream(
                (int) multipart.getTotalLength());
        multipart.writeTo(debugEntity);
        String multipartRequest = debugEntity.toString();
        Log.e(TDDatabase.TAG, "DEBUG multipart multipart: \n"
                + multipartRequest);

        Log.d(TDDatabase.TAG,
                "length of multipart is " + multipart.getTotalLength());

    }

    public static JSONObject executeAndLogHTTPResponse(HttpUriRequest request) {
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

            Log.i(TDDatabase.TAG, "Response text: " + responseText);

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

}
