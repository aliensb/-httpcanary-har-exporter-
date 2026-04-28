package dev.codex.httpcanary.har;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class HarExporter {
    private static final int BODY_LIMIT_BYTES = 10 * 1024 * 1024;

    private HarExporter() {
    }

    static String exportSession(Context context, ClassLoader classLoader, Object captureSession) throws Exception {
        List<String> ids = getStringList(captureSession, "records", "getRecords");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("session has no records");
        }

        List<Object> records = loadRecords(classLoader, ids);
        JSONObject har = buildHar(captureSession, records);

        File outDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "HttpCanaryHar"
        );
        if ((!outDir.exists() && !outDir.mkdirs()) || !outDir.isDirectory()) {
            outDir = new File(context.getExternalFilesDir(null), "har");
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new IllegalStateException("cannot create export directory");
            }
        }

        long startTime = getLong(captureSession, "startTime", "getStartTime", System.currentTimeMillis());
        Long id = getLongObject(captureSession, "id", "getId");
        String name = "httpcanary-session-"
                + (id != null ? id : startTime)
                + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(startTime))
                + ".har";
        File outFile = new File(outDir, safeFileName(name));

        FileOutputStream output = new FileOutputStream(outFile);
        try {
            output.write(har.toString(2).getBytes("UTF-8"));
        } finally {
            output.close();
        }
        return outFile.getAbsolutePath();
    }

    private static List<Object> loadRecords(ClassLoader classLoader, List<String> ids) throws Exception {
        Class<?> appClass = Class.forName("com.guoshi.httpcanary.App", false, classLoader);
        Object app = appClass.getDeclaredMethod("getInstance").invoke(null);
        Object daoSession = appClass.getDeclaredMethod("c").invoke(app);
        Object dao = daoSession.getClass().getDeclaredMethod("getHttpCaptureRecordDao").invoke(daoSession);

        Method loadMethod = null;
        Class<?> cls = dao.getClass();
        while (cls != null && loadMethod == null) {
            for (Method method : cls.getDeclaredMethods()) {
                if ("load".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    method.setAccessible(true);
                    loadMethod = method;
                    break;
                }
            }
            cls = cls.getSuperclass();
        }
        if (loadMethod == null) {
            throw new NoSuchMethodException("HttpCaptureRecordDao.load");
        }

        List<Object> records = new ArrayList<Object>();
        for (String id : ids) {
            Object record = loadMethod.invoke(dao, id);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    private static JSONObject buildHar(Object captureSession, List<Object> records) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject log = new JSONObject();
        root.put("log", log);

        log.put("version", "1.2");
        log.put("creator", new JSONObject()
                .put("name", "HttpCanary HAR Exporter")
                .put("version", "1.0.0"));

        String pageId = "session-" + valueOrDefault(getObject(captureSession, "id", "getId"), getLong(captureSession, "startTime", "getStartTime", 0L));
        long start = getLong(captureSession, "startTime", "getStartTime", System.currentTimeMillis());
        long stop = getLong(captureSession, "stopTime", "getStopTime", start);

        JSONArray pages = new JSONArray();
        pages.put(new JSONObject()
                .put("startedDateTime", iso(start))
                .put("id", pageId)
                .put("title", "HttpCanary session")
                .put("pageTimings", new JSONObject()
                        .put("onContentLoad", -1)
                        .put("onLoad", Math.max(0L, stop - start))));
        log.put("pages", pages);

        JSONArray entries = new JSONArray();
        for (Object record : records) {
            entries.put(buildEntry(pageId, record));
        }
        log.put("entries", entries);
        return root;
    }

    private static JSONObject buildEntry(String pageId, Object record) throws Exception {
        long started = getLong(record, "time", "getTime", System.currentTimeMillis());
        long duration = getLong(record, "duration", "getDuration", 0L);

        JSONObject entry = new JSONObject();
        entry.put("pageref", pageId);
        entry.put("startedDateTime", iso(started));
        entry.put("time", Math.max(0L, duration));
        entry.put("request", buildRequest(record));
        entry.put("response", buildResponse(record));
        entry.put("cache", new JSONObject());
        entry.put("timings", new JSONObject()
                .put("blocked", -1)
                .put("dns", -1)
                .put("connect", -1)
                .put("ssl", -1)
                .put("send", 0)
                .put("wait", Math.max(0L, duration))
                .put("receive", 0));

        String remoteIp = getString(record, "remoteIp", "getRemoteIp");
        if (remoteIp != null) {
            entry.put("serverIPAddress", remoteIp);
        }
        return entry;
    }

    private static JSONObject buildRequest(Object record) throws Exception {
        String method = enumName(getObject(record, "method", "getMethod"));
        String url = getString(record, "url", "getUrl");
        List<?> headers = getList(record, "reqHeaders", "getReqHeaders");
        byte[] body = readBody(
                getString(record, "reqFilePath", "getReqFilePath"),
                getInt(record, "reqBodyOffset", "getReqBodyOffset", 0)
        );

        JSONObject request = new JSONObject();
        request.put("method", method != null ? method : "GET");
        request.put("url", url != null ? url : "");
        request.put("httpVersion", httpVersion(record));
        request.put("cookies", new JSONArray());
        request.put("headers", headersToJson(headers));
        request.put("queryString", queryString(url));
        request.put("headersSize", -1);
        request.put("bodySize", body == null ? 0 : body.length);
        if (body != null && body.length > 0) {
            request.put("postData", bodyToPostData(headers, body));
        }
        return request;
    }

    private static JSONObject buildResponse(Object record) throws Exception {
        List<?> headers = getList(record, "resHeaders", "getResHeaders");
        byte[] body = readBody(
                getString(record, "resFilePath", "getResFilePath"),
                getInt(record, "resBodyOffset", "getResBodyOffset", 0)
        );

        JSONObject response = new JSONObject();
        response.put("status", getInt(record, "code", "getCode", 0));
        response.put("statusText", valueOrEmpty(getString(record, "message", "getMessage")));
        response.put("httpVersion", httpVersion(record));
        response.put("cookies", new JSONArray());
        response.put("headers", headersToJson(headers));
        response.put("content", bodyToContent(headers, body));
        response.put("redirectURL", firstHeader(headers, "Location", ""));
        response.put("headersSize", -1);
        response.put("bodySize", body == null ? 0 : body.length);
        return response;
    }

    private static JSONObject bodyToPostData(List<?> headers, byte[] body) throws Exception {
        JSONObject postData = new JSONObject();
        postData.put("mimeType", firstHeader(headers, "Content-Type", "application/octet-stream"));
        putBodyText(postData, headers, body);
        return postData;
    }

    private static JSONObject bodyToContent(List<?> headers, byte[] body) throws Exception {
        JSONObject content = new JSONObject();
        content.put("size", body == null ? 0 : body.length);
        content.put("mimeType", firstHeader(headers, "Content-Type", "application/octet-stream"));
        if (body != null && body.length > 0) {
            putBodyText(content, headers, body);
        }
        return content;
    }

    private static void putBodyText(JSONObject object, List<?> headers, byte[] body) throws Exception {
        if (isTextual(headers, body)) {
            object.put("text", new String(body, charsetFromContentType(firstHeader(headers, "Content-Type", null))));
        } else {
            object.put("text", Base64.encodeToString(body, Base64.NO_WRAP));
            object.put("encoding", "base64");
        }
    }

    private static byte[] readBody(String path, int offset) throws Exception {
        if (path == null || path.length() == 0) {
            return null;
        }
        File file = new File(path);
        if (!file.isFile() || file.length() <= offset) {
            return null;
        }

        FileInputStream input = new FileInputStream(file);
        try {
            long skipped = 0;
            while (skipped < offset) {
                long n = input.skip(offset - skipped);
                if (n <= 0) {
                    return null;
                }
                skipped += n;
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total + read > BODY_LIMIT_BYTES) {
                    output.write(buffer, 0, BODY_LIMIT_BYTES - total);
                    break;
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static JSONArray headersToJson(List<?> entries) throws Exception {
        JSONArray array = new JSONArray();
        if (entries == null) {
            return array;
        }
        for (Object entry : entries) {
            String name = getString(entry, "name", null);
            String value = getString(entry, "value", null);
            if (name != null) {
                array.put(new JSONObject().put("name", name).put("value", valueOrEmpty(value)));
            }
        }
        return array;
    }

    private static JSONArray queryString(String url) throws Exception {
        JSONArray array = new JSONArray();
        if (url == null) {
            return array;
        }
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return array;
        }
        int hash = url.indexOf('#', q + 1);
        String query = url.substring(q + 1, hash >= 0 ? hash : url.length());
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.length() == 0) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            array.put(new JSONObject().put("name", name).put("value", value));
        }
        return array;
    }

    private static String firstHeader(List<?> headers, String name, String defaultValue) throws Exception {
        if (headers == null) {
            return defaultValue;
        }
        for (Object entry : headers) {
            String headerName = getString(entry, "name", null);
            if (headerName != null && headerName.equalsIgnoreCase(name)) {
                String value = getString(entry, "value", null);
                return value != null ? value : defaultValue;
            }
        }
        return defaultValue;
    }

    private static boolean isTextual(List<?> headers, byte[] body) throws Exception {
        String contentType = firstHeader(headers, "Content-Type", "");
        String lower = contentType.toLowerCase(Locale.US);
        if (lower.startsWith("text/")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("javascript")
                || lower.contains("x-www-form-urlencoded")) {
            return true;
        }
        int check = Math.min(body.length, 512);
        for (int i = 0; i < check; i++) {
            byte b = body[i];
            if (b == 0) {
                return false;
            }
        }
        return false;
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType != null) {
            String[] parts = contentType.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.US).startsWith("charset=")) {
                    try {
                        return Charset.forName(trimmed.substring("charset=".length()).replace("\"", ""));
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        }
        return Charset.forName("UTF-8");
    }

    private static String httpVersion(Object record) throws Exception {
        String protocol = enumName(getObject(record, "protocol", "getProtocol"));
        return protocol != null ? protocol.replace('_', '/') : "HTTP/1.1";
    }

    private static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String safeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : value == null ? null : String.valueOf(value);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Object valueOrDefault(Object value, Object defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Object getObject(Object receiver, String fieldName, String getterName) throws Exception {
        if (getterName != null) {
            try {
                Method method = receiver.getClass().getMethod(getterName);
                method.setAccessible(true);
                return method.invoke(receiver);
            } catch (NoSuchMethodException ignored) {
            }
        }
        Field field = findField(receiver.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static String getString(Object receiver, String fieldName, String getterName) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        return value == null ? null : String.valueOf(value);
    }

    private static int getInt(Object receiver, String fieldName, String getterName, int defaultValue) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    private static long getLong(Object receiver, String fieldName, String getterName, long defaultValue) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        return value instanceof Number ? ((Number) value).longValue() : defaultValue;
    }

    private static Long getLongObject(Object receiver, String fieldName, String getterName) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static List<?> getList(Object receiver, String fieldName, String getterName) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        return value instanceof List ? (List<?>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Object receiver, String fieldName, String getterName) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        if (!(value instanceof Collection)) {
            return null;
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (Collection<Object>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
