package com.flue.launcher.diagnostics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

public class DiagnosticsRecordingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Object companion() throws Exception {
        return Class.forName("com.flue.launcher.diagnostics.DiagnosticsRecording").getField("Companion").get(null);
    }

    private Object create(File root) throws Exception {
        Object companion = companion();
        return companion.getClass().getMethod("create", File.class, long.class, String.class)
            .invoke(companion, root, 1000L, "{\"schema_version\":1}");
    }

    @Test public void exportedZipContainsFlushedCsvAndStopsAtDeadline() throws Exception {
        File root = temporary.newFolder();
        Object recording = create(root);
        Class<?> frameType = Class.forName("com.flue.launcher.diagnostics.FrameSample");
        Object frame = frameType.getConstructor().newInstance();
        Class<?> processType = Class.forName("com.flue.launcher.diagnostics.ProcessSample");
        Object process = processType.getConstructor().newInstance();
        Method append = recording.getClass().getMethod("append", long.class, String.class, frameType, processType);
        append.invoke(recording, 2000L, "SettingsActivity", frame, process);
        append.invoke(recording, 301000L, "ShouldNotAppear", frame, process);
        File archive = (File) recording.getClass().getMethod("finish", long.class, String.class).invoke(recording, 900000L, "deadline");
        try (ZipFile zip = new ZipFile(archive)) {
            assertEquals(3, zip.size());
            String csv = new String(zip.getInputStream(zip.getEntry("samples.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(csv.contains("1000,SettingsActivity"));
            assertFalse(csv.contains("ShouldNotAppear"));
            String session = new String(zip.getInputStream(zip.getEntry("session.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(session.contains("\"elapsed_ms\":300000"));
            assertTrue(session.contains("\"reason\":\"deadline\""));
            assertEquals("{\"schema_version\":1}", new String(zip.getInputStream(zip.getEntry("info.json")).readAllBytes(), StandardCharsets.UTF_8));
        }
        Object helper = companion();
        assertEquals(archive, helper.getClass().getMethod("recoverLatest", File.class).invoke(helper, root));
    }

    @Test public void processDeathRecoveryPreservesPartialDataAndLabelsIt() throws Exception {
        File root = temporary.newFolder();
        File partial = new File(root, "flue-performance-interrupted");
        assertTrue(partial.mkdir());
        Files.write(new File(partial, "info.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(partial, "samples.csv").toPath(), "elapsed_ms,activity\n1000,LauncherActivity\n".getBytes(StandardCharsets.UTF_8));
        Object helper = companion();
        File archive = (File) helper.getClass().getMethod("recoverLatest", File.class).invoke(helper, root);
        assertTrue(archive.isFile());
        try (ZipFile zip = new ZipFile(archive)) {
            String session = new String(zip.getInputStream(zip.getEntry("session.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(session.contains("\"partial\":true"));
            assertTrue(session.contains("process_interrupted"));
            assertTrue(session.contains("\"elapsed_ms\":null"));
            assertTrue(new String(zip.getInputStream(zip.getEntry("samples.csv")).readAllBytes(), StandardCharsets.UTF_8).contains("LauncherActivity"));
        }
        assertTrue(new File(partial, "samples.csv").exists());
    }

    @Test public void allSwitchesDefaultOffAndChangingOnePreservesOthers() throws Exception {
        Class<?> type = Class.forName("com.flue.launcher.diagnostics.DiagnosticsConfig");
        Object defaults = type.getConstructor().newInstance();
        assertEquals(false, type.getMethod("getAnyEnabled").invoke(defaults));
        Object enabled = type.getConstructor(boolean.class, boolean.class, boolean.class).newInstance(true, false, true);
        Object memoryOn = type.getMethod("copy", boolean.class, boolean.class, boolean.class).invoke(enabled, true, true, true);
        assertEquals(true, type.getMethod("getFps").invoke(memoryOn));
        assertEquals(true, type.getMethod("getThreads").invoke(memoryOn));
    }
}
