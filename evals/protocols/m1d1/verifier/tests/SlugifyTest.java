import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
public class SlugifyTest {
    @BeforeAll static void ready() {
        System.out.println("M1D1_CHECK_READY");
    }
    @BeforeEach void allowExecution() {
        Assumptions.assumeFalse("skip".equals(System.getProperty("control")), "Trusted all-skipped control");
    }
    @Test void normalizesSpaces() { assertEquals("hello-world", Slugify.slugify(" Hello World ")); }
    @Test void collapsesSeparators() { assertEquals("a-b", Slugify.slugify("A---B")); }
    @Test void handlesEmpty() { assertEquals("", Slugify.slugify("  ")); }
}
