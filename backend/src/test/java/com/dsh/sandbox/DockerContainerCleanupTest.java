package com.dsh.sandbox;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.dsh.sandbox.DockerContainerCleanup.Presence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DockerContainerCleanupTest {
    private static final String NAME = "dsh-op-c19";

    private Process reply(int exit, String output) throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(exit);
        return process;
    }

    @Test void successfulEmptyListProvesAbsent() throws Exception {
        assertEquals(ABSENT, new DockerContainerCleanup(cmd -> replyUnchecked(0, "")).presence(NAME));
    }

    private Process replyUnchecked(int exit, String output) {
        try { return reply(exit, output); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void exactNameProvesPresentAndFilterIsAnchored() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        Process process = reply(0, NAME + "\n");
        DockerContainerCleanup cleanup = new DockerContainerCleanup(cmd -> { commands.add(cmd); return process; });
        assertEquals(PRESENT, cleanup.presence(NAME));
        assertEquals(List.of("docker", "container", "ls", "--all", "--filter",
                "name=^/" + NAME + "$", "--format", "{{.Names}}"), commands.getFirst());
    }

    @Test void substringDoesNotProvePresent() throws Exception {
        Process process = reply(0, NAME + "-other\n");
        assertEquals(ABSENT, new DockerContainerCleanup(cmd -> process).presence(NAME));
    }

    @Test void daemonFailureEvenWithEmptyOutputIsUnknown() throws Exception {
        Process process = reply(1, "");
        assertEquals(UNKNOWN, new DockerContainerCleanup(cmd -> process).presence(NAME));
    }

    @Test void launchExceptionIsUnknown() {
        assertEquals(UNKNOWN, new DockerContainerCleanup(cmd -> { throw new IOException("no docker"); }).presence(NAME));
    }

    @Test void timeoutIsUnknownAndControlProcessIsDestroyed() throws Exception {
        Process process = reply(0, "");
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(process.isAlive()).thenReturn(true);
        assertEquals(UNKNOWN, new DockerContainerCleanup(cmd -> process).presence(NAME));
        verify(process).destroyForcibly();
    }

    @Test void truncatedResponseCannotProveAbsence() throws Exception {
        Process process = reply(0, "other-container\n".repeat(2000));
        assertEquals(UNKNOWN, new DockerContainerCleanup(cmd -> process).presence(NAME));
    }

    @Test void readFailureCannotProveAbsence() throws Exception {
        Process process = reply(0, "");
        when(process.getInputStream()).thenReturn(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("broken stream"); }
        });
        assertEquals(UNKNOWN, new DockerContainerCleanup(cmd -> process).presence(NAME));
    }

    @Test void malformedSuccessfulResponseIsUnknown() throws Exception {
        Process process = reply(0, "Cannot connect to daemon\n");
        assertEquals(UNKNOWN, new DockerContainerCleanup(cmd -> process).presence(NAME));
    }

    @Test void exactRemovalAcknowledgementConfirmsCleanupWithoutQuery() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        Process process = reply(0, NAME + "\n");
        DockerContainerCleanup cleanup = new DockerContainerCleanup(cmd -> { commands.add(cmd); return process; });
        assertTrue(cleanup.removeAndConfirm(NAME));
        assertEquals(List.of(List.of("docker", "rm", "-f", NAME)), commands);
    }

    @Test void failedRemovalCanBeConfirmedByFreshAbsentQuery() throws Exception {
        Process rm = reply(1, "No such container\n"), ls = reply(0, "");
        DockerContainerCleanup cleanup = new DockerContainerCleanup(cmd -> cmd.get(1).equals("rm") ? rm : ls);
        assertTrue(cleanup.removeAndConfirm(NAME));
    }

    @Test void failedRemovalAndDaemonFailureNeverConfirmCleanup() throws Exception {
        Process rm = reply(1, "Cannot connect\n"), ls = reply(1, "");
        DockerContainerCleanup cleanup = new DockerContainerCleanup(cmd -> cmd.get(1).equals("rm") ? rm : ls);
        assertFalse(cleanup.removeAndConfirm(NAME));
    }

    @Test void removalSubstringAndStillPresentNeverConfirmCleanup() throws Exception {
        Process rm = reply(0, NAME + "-other\n"), ls = reply(0, NAME + "\n");
        DockerContainerCleanup cleanup = new DockerContainerCleanup(cmd -> cmd.get(1).equals("rm") ? rm : ls);
        assertFalse(cleanup.removeAndConfirm(NAME));
    }

    @Test void unreapedCliStillAttemptsContainerRemovalButCannotConfirmBoth() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        Process process = reply(0, NAME + "\n");
        DockerContainerCleanup cleanup = new DockerContainerCleanup(cmd -> { commands.add(cmd); return process; });
        assertFalse(cleanup.afterTimeout(NAME, false));
        assertEquals("rm", commands.getFirst().get(1));
        assertTrue(cleanup.afterTimeout(NAME, true));
    }

    @Test void interruptIsPreservedAndControlProcessIsDestroyed() throws Exception {
        Process process = reply(0, "");
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        when(process.isAlive()).thenReturn(true);
        try {
            assertEquals(UNKNOWN, new DockerContainerCleanup(cmd -> process).presence(NAME));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(process).destroyForcibly();
        } finally { Thread.interrupted(); }
    }

    @Test void invalidNamesNeverReachDocker() {
        DockerContainerCleanup cleanup = new DockerContainerCleanup(cmd -> { throw new AssertionError("unexpected Docker call"); });
        assertEquals(UNKNOWN, cleanup.presence(null));
        assertEquals(UNKNOWN, cleanup.presence("dsh-.*"));
        assertFalse(cleanup.removeAndConfirm("--all"));
    }
}
