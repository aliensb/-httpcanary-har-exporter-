package dev.codex.httpcanary.har;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.guoshi.httpcanary";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        try {
            hookHistorySessionLongPress(lpparam.classLoader);
            hookRecordUrlLine(lpparam.classLoader);
            XposedBridge.log("HttpCanaryHarExporter: hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("HttpCanaryHarExporter: install hook failed");
            XposedBridge.log(t);
        }
    }

    private static void hookHistorySessionLongPress(final ClassLoader classLoader) {
        Class<?> sessionClass = XposedHelpers.findClass(
                "com.guoshi.httpcanary.db.CaptureSession",
                classLoader
        );

        XposedHelpers.findAndHookMethod(
                "com.guoshi.httpcanary.ui.others.HistoriesActivity",
                classLoader,
                "b",
                sessionClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) {
                        final Activity activity = (Activity) param.thisObject;
                        final Object captureSession = param.args[0];

                        showSessionMenu(activity, classLoader, captureSession);
                        param.setResult(true);
                    }
                }
        );
    }

    private static void hookRecordUrlLine(final ClassLoader classLoader) {
        Class<?> recordClass = XposedHelpers.findClass(
                "com.guoshi.httpcanary.db.HttpCaptureRecord",
                classLoader
        );

        hookRecordAdapter(classLoader, "com.guoshi.httpcanary.ui.a.b", recordClass);
        hookRecordAdapter(classLoader, "com.guoshi.httpcanary.ui.a.c", recordClass);
    }

    private static void hookRecordAdapter(
            final ClassLoader classLoader,
            String adapterClassName,
            Class<?> recordClass
    ) {
        XposedHelpers.findAndHookMethod(
                adapterClassName,
                classLoader,
                "a",
                android.view.View.class,
                recordClass,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        UiTweaks.splitHostAndUri((android.view.View) param.args[0], param.args[1]);
                    }
                }
        );
    }

    private static void showSessionMenu(
            final Activity activity,
            final ClassLoader classLoader,
            final Object captureSession
    ) {
        new AlertDialog.Builder(activity)
                .setTitle("Choose the action")
                .setItems(new CharSequence[]{"Export HAR", "Delete"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            exportSession(activity, classLoader, captureSession);
                        } else if (which == 1) {
                            deleteSession(activity, captureSession);
                        }
                    }
                })
                .show();
    }

    private static void exportSession(
            final Activity activity,
            final ClassLoader classLoader,
            final Object captureSession
    ) {
        Toast.makeText(activity, "Exporting HAR...", Toast.LENGTH_SHORT).show();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String path = HarExporter.exportSession(activity, classLoader, captureSession);
                    postToast(activity, "HAR exported: " + path, Toast.LENGTH_LONG);
                } catch (final Throwable t) {
                    XposedBridge.log("HttpCanaryHarExporter: export failed");
                    XposedBridge.log(t);
                    postToast(activity, "HAR export failed: " + t.getMessage(), Toast.LENGTH_LONG);
                }
            }
        }, "httpcanary-har-exporter");
        worker.start();
    }

    private static void deleteSession(Activity activity, Object captureSession) {
        try {
            Object adapter = invokeNoArg(activity, "m");
            if (adapter != null) {
                invokeBest(adapter, "b", captureSession);
                invokeNoArg(adapter, "notifyDataSetChanged");
            }

            Object dao = findFieldOrGetter(activity, "l", null);
            if (dao != null) {
                invokeBest(dao, "delete", captureSession);
            }

            Toast.makeText(activity, "Deleted", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            XposedBridge.log("HttpCanaryHarExporter: delete failed");
            XposedBridge.log(t);
            Toast.makeText(activity, "Delete failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void postToast(final Activity activity, final String message, final int duration) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, message, duration).show();
            }
        });
    }

    private static Object invokeNoArg(Object receiver, String name) throws Exception {
        Class<?> cls = receiver.getClass();
        while (cls != null) {
            try {
                Method method = cls.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(receiver);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeBest(Object receiver, String name, Object arg) throws Exception {
        Class<?> cls = receiver.getClass();
        while (cls != null) {
            Method[] methods = cls.getDeclaredMethods();
            for (Method method : methods) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == 1) {
                    Class<?> paramType = method.getParameterTypes()[0];
                    if (arg == null || paramType.isAssignableFrom(arg.getClass())) {
                        method.setAccessible(true);
                        return method.invoke(receiver, arg);
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private static Object findFieldOrGetter(Object receiver, String fieldName, String getterName) throws Exception {
        Class<?> cls = receiver.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        if (getterName != null) {
            return invokeNoArg(receiver, getterName);
        }
        return null;
    }
}
