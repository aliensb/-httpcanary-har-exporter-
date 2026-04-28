package dev.codex.httpcanary.har;

import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class UiTweaks {
    private UiTweaks() {
    }

    static void splitHostAndUri(View itemView, Object record) {
        try {
            if (itemView == null || record == null) {
                return;
            }

            int requestLineId = itemView.getResources().getIdentifier(
                    "id00b4",
                    "id",
                    itemView.getContext().getPackageName()
            );
            if (requestLineId == 0) {
                return;
            }

            TextView requestLine = itemView.findViewById(requestLineId);
            if (requestLine == null) {
                return;
            }

            String method = enumName(getObject(record, "method", "getMethod"));
            String url = getString(record, "url", "getUrl");
            String host = getString(record, "host", "getHost");
            if (TextUtils.isEmpty(url)) {
                return;
            }

            Uri uri = Uri.parse(url);
            if (TextUtils.isEmpty(host)) {
                host = uri.getHost();
            }
            if (TextUtils.isEmpty(host)) {
                return;
            }

            String path = uri.getEncodedPath();
            String query = uri.getEncodedQuery();
            String fragment = uri.getEncodedFragment();
            StringBuilder uriPart = new StringBuilder();
            uriPart.append(TextUtils.isEmpty(path) ? "/" : path);
            if (!TextUtils.isEmpty(query)) {
                uriPart.append('?').append(query);
            }
            if (!TextUtils.isEmpty(fragment)) {
                uriPart.append('#').append(fragment);
            }

            StringBuilder firstLine = new StringBuilder();
            if (!TextUtils.isEmpty(method)) {
                firstLine.append(method).append(' ');
            }
            firstLine.append(host);

            requestLine.setSingleLine(false);
            requestLine.setMaxLines(2);
            requestLine.setEllipsize(TextUtils.TruncateAt.END);
            requestLine.setText(firstLine.toString() + "\n" + uriPart);
        } catch (Throwable ignored) {
            // UI tweak only; leave the original row untouched on unexpected layouts.
        }
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

    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : value == null ? null : String.valueOf(value);
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
