package com.couchbase.touchdb;

import java.io.IOException;

import org.apache.http.entity.mime.MultipartEntity;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.util.ByteArrayBuilder;

import com.couchbase.touchdb.replicator.MultiPartWriter;

import android.util.Log;

public class TDObjectMapper extends ObjectMapper {

    /**
     * Method that can be used to serialize any Java value as a byte array.
     * Functionally equivalent to calling {@link #writeValue(Writer,Object)}
     * with {@link java.io.ByteArrayOutputStream} and getting bytes, but more
     * efficient. Encoding used will be UTF-8.
     * 
     * See the TouchDB-iOS version: 
     * https://github.com/couchbaselabs/TouchDB-iOS/blob/master/Source/TDMultipartWriter.m
     * 
     * And the commits:
     * https://github.com/couchbaselabs/TouchDB-iOS/commit/2bcc5167fdf4cad50ae285da393a427fcd387d53
     * 
     * @since 1.5
     */
    public MultiPartWriter writeValueAsMultipart(Object value)
            throws IOException, JsonGenerationException, JsonMappingException {
        String result = "";
        /*
         * Handle multipart attachments
         */
        JsonNode obj = valueToTree(value);
        if (obj.has("_attachments")) {
            Log.d(TDDatabase.TAG,
                    "This document has attachments, multiparting them if requested.");
            String fakeMultiPartThatShouldWork = writeValueAsString(value);
            // String fakeMultiPartThatShouldWork =
            // "{\"new_edits\":false,\"docs\":[{\"_rev\":\"2-f39b47a2-ce66-4baa-8b12-04e95b1f5099\",\"foo\":1,\"_id\":\"doc1\",\"bar\":false,\"_revisions\":{\"start\":2,\"ids\":[\"f39b47a2-ce66-4baa-8b12-04e95b1f5099\",\"7874ae85-0772-4851-be27-d1a64475d09b\"]},\"UPDATED\":true},{\"_rev\":\"1-6603271e-f5e7-47e6-b455-b1c1532b5f56\",\"fnord\":true,\"_id\":\"doc2\",\"baz\":666,\"_revisions\":{\"start\":1,\"ids\":[\"6603271e-f5e7-47e6-b455-b1c1532b5f56\"]},\"_attachments\":{\"sample_attachment_image1.jpg\":{\"length\":1381043,\"follows\":true,\"digest\":\"sha1-ja+biiAKyTeZQ/q10wI62g10q9E=\",\"revpos\":1,\"content_type\":\"image/jpeg\"},\"sample_attachment.html\":{\"length\":44,\"follows\":true,\"digest\":\"sha1-7YSFNO4SgkOf8vhbU1/fptQGTr0=\",\"revpos\":1,\"content_type\":\"text/html\"},\"_multipartAttachmentFollows1362931554934\":\"/data/data/com.couchbase.touchdb.testapp/files/touchdb-test/attachments/8daf9b8a200ac9379943fab5d3023ada0d74abd1.blob\",\"_multipartAttachmentFollows1362931554930\":\"/data/data/com.couchbase.touchdb.testapp/files/touchdb-test/attachments/ed848534ee1282439ff2f85b535fdfa6d4064ebd.blob\"}}]}";
            // //writeValueAsString(value);
            result = fakeMultiPartThatShouldWork;// bb.toByteArray();

        } else {
            ByteArrayBuilder bb = new ByteArrayBuilder(
                    _jsonFactory._getBufferRecycler());
            _configAndWriteValue(
                    _jsonFactory.createJsonGenerator(bb, JsonEncoding.UTF8),
                    value);

            result = bb.toByteArray();
            bb.release();
        }

        return new MultiPartWriter();
    }
}
