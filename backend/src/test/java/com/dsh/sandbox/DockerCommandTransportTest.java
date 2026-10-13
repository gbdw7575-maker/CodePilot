package com.dsh.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DockerCommandTransportTest {
    private record Result(int exit, String text) {}

    private Result execute(String command) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", DockerSandboxRunner.commandBootstrap())
                .redirectErrorStream(true).start();
        try (var input = p.getOutputStream()) {
            input.write(DockerSandboxRunner.encodedCommandBytes(command));
        }
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("transport test timed out");
        }
        return new Result(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void commandIsNotInArgvAndUtf8BytesRoundTripExactly() {
        String command = "printf '%s\\n' \"中文 \\\"quoted\\\"\"; echo '$p|[x]'\n\n";
        String arg = DockerSandboxRunner.commandBootstrap();
        assertFalse(arg.contains("\""));
        assertFalse(arg.contains(command));
        assertArrayEquals(command.getBytes(StandardCharsets.UTF_8),
                Base64.getDecoder().decode(DockerSandboxRunner.encodedCommandBytes(command)));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void quotesUnicodeNewlinesRegexAndSedDollarArePreserved() throws Exception {
        String command = "printf '%s\\n' \"quoted space\" '中文' 'WARNING|^\\[' '/=== POT ===/,$p'\n"
                + "printf '=== POT ===\\nvalue\\n' | sed -n '/=== POT ===/,$p'\n";
        Result r = execute(command);
        assertEquals(0, r.exit());
        assertEquals("quoted space\n中文\nWARNING|^\\[\n/=== POT ===/,$p\n=== POT ===\nvalue\n", r.text());
    }

    @Test @EnabledOnOs(OS.LINUX)
    void userScriptStdinIsEofInsteadOfFollowingSourceAndExitIsPreserved() throws Exception {
        Result r = execute("read -r line || echo EOF; printf '%s\\n' done; exit 37");
        assertEquals(37, r.exit());
        assertEquals("EOF\ndone\n", r.text());
    }

    @Test @EnabledOnOs(OS.LINUX)
    void exactScriptBytesAndTemporaryCleanupArePreserved() throws Exception {
        String command = "cat \"$0\"\n\n";
        assertEquals(command, execute(command).text());
        String script = execute("printf '%s' \"$0\"").text();
        assertTrue(script.startsWith("/tmp/dsh-command."));
        assertFalse(Files.exists(Path.of(script)), "temporary script must be removed after execution");
    }

    @Test @EnabledOnOs(OS.LINUX)
    void emptyAndMalformedCommandsKeepRealShellResults() throws Exception {
        assertEquals(0, execute("").exit());
        Result bad = execute("printf '%s' \"unterminated");
        assertNotEquals(0, bad.exit());
        assertTrue(bad.text().contains("unexpected EOF"));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void longSourceDoesNotGrowArgvAndIsFullyExecuted() throws Exception {
        String source = "# 长命令验证\n".repeat(8000) + "printf '%s' \"long command complete\"\n";
        assertTrue(DockerSandboxRunner.encodedCommandBytes(source).length > 32767);
        Result r = execute(source);
        assertEquals(0, r.exit());
        assertEquals("long command complete", r.text());
    }
}
