package com.couchbase.touchdb.testapp.tests;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.HttpMultipart;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import junit.framework.Assert;

import android.util.Log;

import com.couchbase.touchdb.TDBody;
import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDRevision;
import com.couchbase.touchdb.TDStatus;
import com.couchbase.touchdb.support.HttpClientFactory;

public class MultipartUpload extends TouchDBTestCase {

    public void testMultipartUpload() throws Throwable {

        // deleteRemoteDB(getReplicationURL());
        // if (!createCouchDB()) {
        // return;
        // }
        String docid = "docmadeintouchdbuplaodedbymultipartattachment"
                + System.currentTimeMillis();
        String doc_url = getReplicationURL() + "/" + docid + "?new_edits=false";
        JSONObject doc = createTouchDBDocumentWithAttachments(docid);
        String docwithAttachmentFollows = doc.toString(2).replaceAll("stub",
                "follows");
        runMultipartRelatedUpload(doc_url, docwithAttachmentFollows);
    }

    public void testBulkDocsMultipartUpload() throws Throwable {

        // deleteRemoteDB(getReplicationURL());
        // if (!createCouchDB()) {
        // return;
        // }
        String docid = "docmadeintouchdbuplaodedbybulkdocsmultipartattachment"
                + System.currentTimeMillis();
        JSONObject doc = createTouchDBDocumentWithAttachments(docid);
        JSONObject bulkdocs = createBulkDocsFromDoc(doc);
        String bulk_docs_url = getReplicationURL() + "/_bulk_docs";
        String bulkdocswithAttachmentFollows = bulkdocs.toString().replaceAll(
                "stub", "follows");
        runMultipartRelatedUpload(bulk_docs_url, bulkdocswithAttachmentFollows);
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
    public void runMultipartRelatedUpload(String url, String jsonString)
            throws IOException, JSONException {

        HttpMultipart entity = new HttpMultipart("related",
                Charset.defaultCharset(), "abc123");
        entity.addBodyPart(new FormBodyPart("json", new StringBody(jsonString,
                "application/json", Charset.defaultCharset())));
        entity.addBodyPart(new FormBodyPart("bar.txt", new FileBody(new File(
                "/sdcard/bar.txt"), "bar.txt", "text/plain")));
        entity.addBodyPart(new FormBodyPart("foo.txt", new FileBody(new File(
                "/sdcard/foo.txt"), "foo.txt", "text/plain")));
        debugEntity(entity);

        URL uploadURL = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) uploadURL
                .openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty(
                "Content-type",
                " multipart/" + entity.getSubType() + "; boundary="
                        + entity.getBoundary() + "; charset="
                        + entity.getCharset().displayName());
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setFixedLengthStreamingMode((int) entity.getTotalLength());
        DataOutputStream out = new DataOutputStream(
                connection.getOutputStream());
        entity.writeTo(out);
        out.flush();
        out.close();
        connection.disconnect();
        Log.d(TDDatabase.TAG, "Finished uploading to the connection: ");
    }

    public JSONObject createTouchDBDocumentWithAttachments(String docid)
            throws JSONException {
        Map<String, Object> documentProperties = new HashMap<String, Object>();
        documentProperties.put("_id", docid);
        documentProperties.put("body", "here is a body, of a document "
                + "with attachments created in touchdb.");
        TDRevision revision = new TDRevision(new TDBody(documentProperties));
        TDStatus status = new TDStatus();
        revision = database.putRevision(revision, null, false, status);
        Assert.assertEquals(TDStatus.CREATED, status.getCode());
        documentProperties.put("_rev", revision.getRevId());

        // Add two text attachments to the documents:
        status = database.insertAttachmentForSequenceWithNameAndType(
                new ByteArrayInputStream("this is 21 chars long".getBytes()),
                revision.getSequence(), "foo.txt", "text/plain",
                revision.getGeneration());
        Assert.assertEquals(TDStatus.CREATED, status.getCode());
        status = database.insertAttachmentForSequenceWithNameAndType(
                new ByteArrayInputStream("this is 20 chars lon".getBytes()),
                revision.getSequence(), "bar.txt", "text/plain",
                revision.getGeneration());
        Assert.assertEquals(TDStatus.CREATED, status.getCode());

        TDRevision revFromTouchDBWithAttachments = database
                .getDocumentWithIDAndRev(docid, null,
                        EnumSet.noneOf(TDDatabase.TDContentOptions.class));
        Assert.assertNotNull(revFromTouchDBWithAttachments);
        return new JSONObject(revFromTouchDBWithAttachments.getBody()
                .getJSONString());

    }

    public JSONObject createBulkDocsFromDoc(JSONObject doc)
            throws JSONException {
        JSONObject bulkdocs = new JSONObject();
        bulkdocs.put("new_edits", false);
        JSONArray docs = new JSONArray();
        docs.put(doc);
        bulkdocs.put("docs", docs);
        return bulkdocs;
    }

    public boolean createCouchDB() throws MalformedURLException, JSONException {
        URL remote = getReplicationURL();
        HttpPut request = new HttpPut(remote + "");
        JSONObject response = executeAndLogHTTPResponse(request);
        return (Boolean) response.get("ok");
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

    public void debugEntity(HttpMultipart entity) throws IOException {
        ByteArrayOutputStream debugEntity = new ByteArrayOutputStream(
                (int) entity.getTotalLength());
        entity.writeTo(debugEntity);
        String multipartRequest = debugEntity.toString();
        Log.e(TDDatabase.TAG, "DEBUG multipart entity: \n" + multipartRequest);

        Log.d(TDDatabase.TAG, "length of entity is " + entity.getTotalLength());

    }
}