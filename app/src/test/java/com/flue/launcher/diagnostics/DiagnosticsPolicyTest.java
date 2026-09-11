package com.flue.launcher.diagnostics;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DiagnosticsPolicyTest {
    @Test public void deadlineUsesWallElapsedTimeAndIsInclusive() throws Exception {
        Class<?> type = Class.forName("com.flue.launcher.diagnostics.RecordingWindow");
        Object window = type.getConstructor(long.class).newInstance(1000L);
        Method expired = type.getMethod("isExpired", long.class);
        assertEquals(false, expired.invoke(window, 300999L));
        assertEquals(true, expired.invoke(window, 301000L));
        assertEquals(true, expired.invoke(window, 900000L));
        assertEquals(false, expired.invoke(window, 999L));
    }

    @Test public void idleIntervalHasZeroFpsAndDoesNotReusePreviousFrames() throws Exception {
        Class<?> type = Class.forName("com.flue.launcher.diagnostics.FrameIntervalAccumulator");
        Object counter = type.getConstructor().newInstance();
        Method record = type.getMethod("record", long.class, long.class, int.class);
        Method snapshot = type.getMethod("take", long.class);
        record.invoke(counter, 10_000_000L, 16_666_667L, 0);
        record.invoke(counter, 30_000_000L, 16_666_667L, 2);
        Object first = snapshot.invoke(counter, 2000L);
        assertEquals(1.0, (double) first.getClass().getMethod("getFps").invoke(first), 0.001);
        assertEquals(20.0, (double) first.getClass().getMethod("getAverageMs").invoke(first), 0.001);
        assertEquals(50.0, (double) first.getClass().getMethod("getJankPercent").invoke(first), 0.001);
        assertEquals(2L, first.getClass().getMethod("getDroppedCallbacks").invoke(first));
        Object idle = snapshot.invoke(counter, 1000L);
        assertEquals(0.0, (double) idle.getClass().getMethod("getFps").invoke(idle), 0.001);
        assertEquals(0L, idle.getClass().getMethod("getFrames").invoke(idle));
    }

    @Test public void configSnapshotNeverCopiesUnknownKeysOrStructuredValues() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("layout_mode", "List");
        source.put("blur_enabled", true);
        source.put("access_token", "secret");
        source.put("weather_location", "private address");
        source.put("side_screen_widgets", "private serialized data");
        source.put("selected_watchface_id", new Object());
        Class<?> type = Class.forName("com.flue.launcher.diagnostics.DiagnosticsPolicyKt");
        Map<?, ?> actual = (Map<?, ?>) type.getMethod("allowlistedDiagnosticConfig", Map.class).invoke(null, source);
        assertEquals(2, actual.size());
        assertEquals("List", actual.get("layout_mode"));
        assertEquals(true, actual.get("blur_enabled"));
    }
}
