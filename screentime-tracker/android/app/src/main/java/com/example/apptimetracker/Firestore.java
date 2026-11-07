package com.example.apptimetracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Minimal Firestore client using its public web (REST) API. No Firebase library needed. */
final class Firestore {
    /** One document: its id (last part of its path) and its typed "fields" JSON. */
    static final class Doc {
        final String id;
        final JSONObject fields;
        Doc(String id, JSONObject fields) { this.id = id; this.fields = fields; }
    }

    /** One create-or-update of the given fields. Values: String, Long or Integer. */
    static final class Write {
        final String path;
        final Map<String, Object> values;
        Write(String path, Map<String, Object> values) { this.path = path; this.values = values; }
    }

    private final String project;
    private final String apiKey;

    Firestore(String project, String apiKey) {
        this.project = project;
        this.apiKey = apiKey;
    }

    private String root() {
        return "projects/" + project + "/databases/(default)/documents";
    }

    /** Returns the document's "fields" object, or an empty object if the document does not exist. */
    JSONObject getFields(String docPath) throws IOException {
        String body = request("GET", "https://firestore.googleapis.com/v1/" + root() + "/" + docPath
                + "?key=" + apiKey, null);
        if (body == null) return new JSONObject();
        try {
            JSONObject fields = new JSONObject(body).optJSONObject("fields");
            return fields == null ? new JSONObject() : fields;
        } catch (JSONException e) {
            throw new IOException("Bad response: " + e.getMessage());
        }
    }

    /** Returns every document in a collection (follows page tokens). */
    List<Doc> list(String collectionPath) throws IOException {
        List<Doc> out = new ArrayList<>();
        String token = "";
        for (int page = 0; page < 20; page++) {
            String url = "https://firestore.googleapis.com/v1/" + root() + "/" + collectionPath
                    + "?pageSize=300&key=" + apiKey
                    + (token.isEmpty() ? "" : "&pageToken=" + URLEncoder.encode(token, "UTF-8"));
            String body = request("GET", url, null);
            if (body == null) break;
            try {
                JSONObject rootObj = new JSONObject(body);
                JSONArray docs = rootObj.optJSONArray("documents");
                if (docs != null) {
                    for (int i = 0; i < docs.length(); i++) {
                        JSONObject d = docs.getJSONObject(i);
                        String name = d.optString("name", "");
                        JSONObject f = d.optJSONObject("fields");
                        out.add(new Doc(name.substring(name.lastIndexOf('/') + 1),
                                f == null ? new JSONObject() : f));
                    }
                }
                token = rootObj.optString("nextPageToken", "");
            } catch (JSONException e) {
                throw new IOException("Bad response: " + e.getMessage());
            }
            if (token.isEmpty()) break;
        }
        return out;
    }

    /** Creates or updates only the given fields of one document. */
    void write(String docPath, Map<String, Object> values) throws IOException {
        List<Write> one = new ArrayList<>();
        one.add(new Write(docPath, values));
        commit(one);
    }

    /** Applies several writes in one request. */
    void commit(List<Write> writes) throws IOException {
        if (writes.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (Write w : writes) {
                JSONObject fields = new JSONObject();
                JSONArray mask = new JSONArray();
                for (Map.Entry<String, Object> e : w.values.entrySet()) {
                    JSONObject v = new JSONObject();
                    if (e.getValue() instanceof String) v.put("stringValue", e.getValue());
                    else v.put("integerValue", String.valueOf(((Number) e.getValue()).longValue()));
                    fields.put(e.getKey(), v);
                    mask.put(e.getKey());
                }
                JSONObject update = new JSONObject()
                        .put("name", root() + "/" + w.path)
                        .put("fields", fields);
                arr.put(new JSONObject().put("update", update)
                        .put("updateMask", new JSONObject().put("fieldPaths", mask)));
            }
            request("POST", "https://firestore.googleapis.com/v1/" + root() + ":commit?key=" + apiKey,
                    new JSONObject().put("writes", arr).toString());
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    // ---- reading values out of Firestore's typed JSON ----

    static String str(JSONObject fields, String key) {
        JSONObject v = fields.optJSONObject(key);
        return v == null ? "" : v.optString("stringValue", "");
    }

    static long num(JSONObject fields, String key, long def) {
        JSONObject v = fields.optJSONObject(key);
        if (v == null) return def;
        if (v.has("integerValue")) {
            try { return Long.parseLong(v.optString("integerValue")); } catch (NumberFormatException e) { return def; }
        }
        if (v.has("doubleValue")) return (long) v.optDouble("doubleValue", def);
        return def;
    }

    // ---- plain HTTPS ----

    /** Returns the response body, or null for HTTP 404. Throws on other errors. */
    private String request(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod(method);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "" : readAll(in);
            if (code == 404) return null;
            if (code >= 400) {
                throw new IOException(explain(code, text));
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String explain(int code, String text) {
        if (code == 403) return "Firebase refused access (HTTP 403). Check the Firestore rules and API key.";
        if (code == 400 && text.contains("API key")) return "The Firebase API key looks wrong.";
        String t = text.length() > 120 ? text.substring(0, 120) : text;
        return "Firebase error HTTP " + code + ": " + t;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
