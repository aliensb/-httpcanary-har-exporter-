package dev.codex.httpcanary.har;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

final class BodyPreviewInstaller {
    private static final int BODY_LIMIT_BYTES = 256 * 1024;

    private BodyPreviewInstaller() {
    }

    static void install(final Activity activity) {
        try {
            final Object record = getObject(activity, "t", null);
            if (record == null) {
                return;
            }

            int pagerId = activity.getResources().getIdentifier("id01d3", "id", activity.getPackageName());
            int tabsId = activity.getResources().getIdentifier("id01d4", "id", activity.getPackageName());
            if (pagerId == 0 || tabsId == 0) {
                return;
            }

            final View pager = activity.findViewById(pagerId);
            final View tabs = activity.findViewById(tabsId);
            if (pager == null || tabs == null || !(pager.getParent() instanceof ViewGroup)) {
                return;
            }

            final FrameLayout host = wrapPager(pager);
            final View preview = createLoadingView(activity);
            host.addView(preview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            insertPreviewTab(activity, tabs, preview);
            observePagerSelection(pager, preview);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    final View content = buildPreview(activity, record);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            host.removeView(preview);
                            host.addView(content, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            ));
                            content.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }, "httpcanary-body-preview").start();
        } catch (Throwable ignored) {
            // Display tweak only; leave the original detail page unchanged if anything differs.
        }
    }

    private static FrameLayout wrapPager(View pager) {
        ViewGroup parent = (ViewGroup) pager.getParent();
        int index = parent.indexOfChild(pager);
        ViewGroup.LayoutParams params = pager.getLayoutParams();
        parent.removeView(pager);

        FrameLayout host = new FrameLayout(pager.getContext());
        parent.addView(host, index, params);
        host.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return host;
    }

    private static View createLoadingView(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xffffffff);
        ProgressBar bar = new ProgressBar(activity);
        root.addView(bar, centeredWrapContent());
        return root;
    }

    private static View buildPreview(Activity activity, Object record) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xffffffff);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 14);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        addSection(activity, content, "Request Body", readBodyText(record, true));
        addSection(activity, content, "Response Body", readBodyText(record, false));
        return scroll;
    }

    private static void addSection(Activity activity, LinearLayout parent, String title, String body) {
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(0xff222222);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        parent.addView(titleView, matchWrap());

        TextView bodyView = new TextView(activity);
        bodyView.setText(TextUtils.isEmpty(body) ? "(empty)" : body);
        bodyView.setTextColor(0xff444444);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bodyView.setTypeface(Typeface.MONOSPACE);
        bodyView.setTextIsSelectable(true);
        bodyView.setPadding(0, dp(activity, 8), 0, dp(activity, 18));
        parent.addView(bodyView, matchWrap());
    }

    private static void insertPreviewTab(final Activity activity, final View tabs, final View preview) {
        if (!(tabs instanceof ViewGroup)) {
            return;
        }
        ViewGroup tabGroup = findLikelyTabStrip((ViewGroup) tabs);
        if (tabGroup == null) {
            return;
        }

        final TextView previewTab = new TextView(activity);
        previewTab.setText("Preview");
        previewTab.setGravity(Gravity.CENTER);
        previewTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        previewTab.setTypeface(Typeface.DEFAULT_BOLD);
        previewTab.setTextColor(0xff222222);
        previewTab.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        previewTab.setSelected(true);
        previewTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                preview.setVisibility(View.VISIBLE);
                setSelectedState(previewTab, true);
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.0f
        );
        tabGroup.addView(previewTab, 0, params);
    }

    private static void observePagerSelection(final View pager, final View preview) {
        try {
            Method addListener = pager.getClass().getMethod("a", Class.forName(
                    "android.support.v4.view.ViewPager$f",
                    false,
                    pager.getClass().getClassLoader()
            ));
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    pager.getClass().getClassLoader(),
                    new Class[]{Class.forName("android.support.v4.view.ViewPager$f", false, pager.getClass().getClassLoader())},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("b".equals(method.getName()) && args != null && args.length == 1) {
                                preview.setVisibility(View.GONE);
                            }
                            return null;
                        }
                    }
            );
            addListener.invoke(pager, listener);
        } catch (Throwable ignored) {
        }
    }

    private static ViewGroup findLikelyTabStrip(ViewGroup root) {
        if (root instanceof LinearLayout && root.getChildCount() > 0) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup found = findLikelyTabStrip((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void setSelectedState(View view, boolean selected) {
        view.setSelected(selected);
    }

    private static String readBodyText(Object record, boolean request) {
        try {
            String path = getString(record, request ? "reqFilePath" : "resFilePath", request ? "getReqFilePath" : "getResFilePath");
            int offset = getInt(record, request ? "reqBodyOffset" : "resBodyOffset", request ? "getReqBodyOffset" : "getResBodyOffset", 0);
            List<?> headers = getList(record, request ? "reqHeaders" : "resHeaders", request ? "getReqHeaders" : "getResHeaders");
            byte[] body = readBody(path, offset);
            if (body == null || body.length == 0) {
                return "";
            }
            byte[] decodedBody = BodyCodec.decode(body, headers);
            if (!BodyCodec.isTextual(headers, decodedBody)) {
                return "[binary body, " + decodedBody.length + " bytes]";
            }
            return new String(decodedBody, BodyCodec.charsetFromContentType(BodyCodec.firstHeader(headers, "Content-Type", null)));
        } catch (Throwable t) {
            return "[preview failed: " + t.getMessage() + "]";
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
                    output.write("\n[truncated]".getBytes("UTF-8"));
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

    private static List<?> getList(Object receiver, String fieldName, String getterName) throws Exception {
        Object value = getObject(receiver, fieldName, getterName);
        return value instanceof List ? (List<?>) value : null;
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

    private static FrameLayout.LayoutParams centeredWrapContent() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER;
        return params;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static int dp(Activity activity, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                activity.getResources().getDisplayMetrics()
        );
    }
}
