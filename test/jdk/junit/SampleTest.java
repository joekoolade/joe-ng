import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A deliberately mixed suite: passes, a real failure, and per-test state that only a FRESH instance gets right. */
public class SampleTest {

    private int counter;

    @BeforeEach
    public void setUp() {
        counter = 41;
    }

    @Test
    public void arithmetic() {
        assertEquals(42, counter + 1);
    }

    @Test
    public void freshInstancePerTest() {
        counter += 1;                       // if instances were shared this would drift across tests
        assertEquals(42, counter);
    }

    @Test
    public void truth() {
        assertTrue(counter > 0);
    }

    @Test
    public void deliberateFailure() {
        assertEquals(1, 2);                 // must be reported as a failure, not crash the run
    }

    public void notATest() {
        throw new RuntimeException("must never run");
    }
}
