package dev.zhanfg.youtubetranslate;

import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * V0.1 discovery hook.
 *
 * <p>This deliberately hooks a broad Android TextView method only while running inside YouTube.
 * Its purpose is to discover the stable comment renderer/binder. It must be replaced by a
 * YouTube-specific hook before translation is implemented.</p>
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "YouTubeTranslate";
    private static final String TARGET_PACKAGE = "com.google.android.youtube";
    private static final int MAX_PROBE_EVENTS = 400;
    private static final int MAX_TEXT_CHARS = 800;
    private static final int MAX_STACK_FRAMES = 10;
    private static final int MAX_PARENT_DEPTH = 6;

    private final AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private final AtomicInteger probeCount = new AtomicInteger(0);
    private final Map<TextView, String> lastTextByView =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG,
                "module loaded: process=" + param.getProcessName()
                        + ", framework=" + getFrameworkName()
                        + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }

        if (!hookInstalled.compareAndSet(false, true)) {
            return;
        }

        try {
            installTextProbe();
            log(Log.INFO, TAG,
                    "V0.1 probe installed for " + TARGET_PACKAGE
                            + "; open a video comment section and capture logcat.");
        } catch (Throwable throwable) {
            hookInstalled.set(false);
            log(Log.ERROR, TAG, "failed to install V0.1 probe", throwable);
        }
    }

    private void installTextProbe() throws ReflectiveOperationException {
        Method setText = TextView.class.getDeclaredMethod(
                "setText",
                CharSequence.class,
                TextView.BufferType.class
        );

        hook(setText).intercept(chain -> {
            Object textArg = chain.getArg(0);
            Object result = chain.proceed();

            Object receiver = chain.getThisObject();
            if (receiver instanceof TextView view && textArg instanceof CharSequence text) {
                recordCandidate(view, text);
            }

            return result;
        });
    }

    private void recordCandidate(TextView view, CharSequence rawText) {
        int currentCount = probeCount.get();
        if (currentCount >= MAX_PROBE_EVENTS) {
            return;
        }

        String text = normalizeText(rawText);
        if (text.length() < 2 || text.length() > MAX_TEXT_CHARS) {
            return;
        }

        String previous = lastTextByView.put(view, text);
        if (text.equals(previous)) {
            return;
        }

        int event = probeCount.incrementAndGet();
        if (event > MAX_PROBE_EVENTS) {
            return;
        }

        StringBuilder message = new StringBuilder(512);
        message.append("PROBE #").append(event)
                .append(" text=\"").append(escape(text)).append('"')
                .append(" id=").append(resourceName(view))
                .append(" view=").append(view.getClass().getName())
                .append(" visible=").append(view.getVisibility() == View.VISIBLE)
                .append(" parents=").append(parentChain(view))
                .append(" callers=").append(compactStack());

        log(Log.INFO, TAG, message.toString());

        if (event == MAX_PROBE_EVENTS) {
            log(Log.WARN, TAG,
                    "probe event budget reached; restart YouTube to collect another bounded sample.");
        }
    }

    private static String normalizeText(CharSequence input) {
        return input.toString().trim();
    }

    private static String escape(String input) {
        String value = input
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\"", "\\\"");
        return value.length() <= 320 ? value : value.substring(0, 320) + "…";
    }

    private static String resourceName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return "NO_ID";
        }
        try {
            return view.getResources().getResourceName(id);
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String parentChain(View view) {
        StringBuilder out = new StringBuilder();
        ViewParent parent = view.getParent();
        int depth = 0;

        while (parent != null && depth < MAX_PARENT_DEPTH) {
            if (depth > 0) {
                out.append(" <- ");
            }
            out.append(parent.getClass().getName());
            if (parent instanceof View parentView) {
                out.append('[').append(resourceName(parentView)).append(']');
            }
            parent = parent.getParent();
            depth++;
        }
        return out.toString();
    }

    private static String compactStack() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        StringBuilder out = new StringBuilder();
        int accepted = 0;

        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.startsWith("android.widget.TextView")
                    || className.startsWith("java.lang.reflect.")
                    || className.startsWith("jdk.internal.reflect.")
                    || className.startsWith(ModuleMain.class.getName())) {
                continue;
            }

            if (accepted > 0) {
                out.append(" <- ");
            }
            out.append(className).append('#').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            accepted++;

            if (accepted >= MAX_STACK_FRAMES) {
                break;
            }
        }

        return out.toString();
    }
}
