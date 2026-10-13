package com.dsh.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class StrictShellCommandTest {
    private record Result(int exit, String text) {}

    private Result execute(String command, boolean strict) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", strict ? StrictShellCommand.wrap(command) : command)
                .redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("strict shell fixture timed out");
        }
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void upstreamFailureIsNotHiddenByTailOrLaterSuccess() throws Exception {
        String command = "(printf 'diagnostic\\n'; exit 23) | tail -1; printf 'should not run'";
        assertEquals(0, execute(command, false).exit());
        Result strict = execute(command, true);
        assertEquals(23, strict.exit());
        assertEquals("diagnostic\n", strict.text());
    }

    @Test void downstreamFailureIsPreserved() throws Exception {
        assertEquals(31, execute("printf x | (cat >/dev/null; exit 31)", true).exit());
    }

    @Test void explicitlyHandledFailureAndConditionalsRemainValid() throws Exception {
        Result result = execute("if false; then exit 7; fi; false || printf 'handled'; printf ' ok'", true);
        assertEquals(0, result.exit());
        assertEquals("handled ok", result.text());
    }

    @Test void literalsUnicodeAndSuccessfulPipelineArePreserved() throws Exception {
        Result result = execute("printf '%s\\n' \"中文 \\\"quote\\\"\" 'single quote' | tail -2", true);
        assertEquals(0, result.exit());
        assertEquals("中文 \"quote\"\nsingle quote\n", result.text());
    }

    @Test void invalidCommandsAreRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> StrictShellCommand.wrap(" "));
        assertThrows(IllegalArgumentException.class, () -> StrictShellCommand.wrap("bad\0command"));
    }
}
