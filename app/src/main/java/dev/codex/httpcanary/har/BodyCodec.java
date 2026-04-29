package dev.codex.httpcanary.har;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class BodyCodec {
    private BodyCodec() {
    }

    static byte[] decode(byte[] body, List<?> headers) throws Exception {
        if (body == null || body.length == 0) {
            return body;
        }
        byte[] decoded = body;
        String transferEncoding = firstHeader(headers, "Transfer-Encoding", "");
        if (containsToken(transferEncoding, "chunked")) {
            decoded = dechunk(decoded);
        }

        String contentEncoding = firstHeader(headers, "Content-Encoding", "");
        if (containsToken(contentEncoding, "gzip")) {
            decoded = readAll(new GZIPInputStream(new ByteArrayInputStream(decoded)));
        } else if (containsToken(contentEncoding, "deflate")) {
            decoded = readAll(new InflaterInputStream(new ByteArrayInputStream(decoded)));
        }
        return decoded;
    }

    static boolean isTextual(List<?> headers, byte[] body) throws Exception {
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
            if (body[i] == 0) {
                return false;
            }
        }
        return false;
    }

    static Charset charsetFromContentType(String contentType) {
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

    static String firstHeader(List<?> headers, String name, String defaultValue) throws Exception {
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

    private static byte[] dechunk(byte[] body) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < body.length) {
            int lineEnd = findCrlf(body, pos);
            if (lineEnd < 0) {
                return body;
            }
            String sizeLine = new String(body, pos, lineEnd - pos, "US-ASCII").trim();
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) {
                sizeLine = sizeLine.substring(0, semicolon).trim();
            }
            int size = Integer.parseInt(sizeLine, 16);
            pos = lineEnd + 2;
            if (size == 0) {
                break;
            }
            if (pos + size > body.length) {
                return body;
            }
            output.write(body, pos, size);
            pos += size;
            if (pos + 1 < body.length && body[pos] == '\r' && body[pos + 1] == '\n') {
                pos += 2;
            }
        }
        return output.toByteArray();
    }

    private static int findCrlf(byte[] body, int start) {
        for (int i = start; i + 1 < body.length; i++) {
            if (body[i] == '\r' && body[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsToken(String value, String token) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split(",");
        for (String part : parts) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(java.io.InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String getString(Object receiver, String fieldName, String getterName) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        return value == null ? null : String.valueOf(value);
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
