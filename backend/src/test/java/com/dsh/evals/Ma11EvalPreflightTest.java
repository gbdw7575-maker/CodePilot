package com.dsh.evals;
import com.dsh.application.DshProperties;
import com.dsh.evals.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ma11EvalPreflightTest {
    final String digest="sha256:"+"a".repeat(64);
    final DshProperties props=mock(DshProperties.class,RETURNS_DEEP_STUBS);
    final EvalConfig config=mock(EvalConfig.class);
    final EvalPreflight preflight=new EvalPreflight(props);
    Ma11EvalPreflightTest(){when(config.seed()).thenReturn(null);when(config.strategy()).thenReturn(EvalStrategy.SINGLE);when(config.imageDigest()).thenReturn(digest);
        when(props.sandbox().mode()).thenReturn("docker");when(props.sandbox().image()).thenReturn(digest);}
    @Test void immutableImageAndMatchingStrategyPass(){assertDoesNotThrow(()->preflight.validate(config));
        when(config.strategy()).thenReturn(EvalStrategy.MULTI);when(props.orchestration().enabled()).thenReturn(true);
        when(props.sandbox().image()).thenReturn("registry/image@"+digest);assertDoesNotThrow(()->preflight.validate(config));}
    @Test void rejectsStrategyMismatch(){when(props.orchestration().enabled()).thenReturn(true);assertThrows(IllegalArgumentException.class,()->preflight.validate(config));}
    @Test void rejectsMutableOrDifferentImage(){when(props.sandbox().image()).thenReturn("dsh-sandbox:latest");assertThrows(IllegalArgumentException.class,()->preflight.validate(config));}
    @Test void rejectsLocalMode(){when(props.sandbox().mode()).thenReturn("local");assertThrows(IllegalArgumentException.class,()->preflight.validate(config));}
    @Test void rejectsSynchronousExecution(){when(props.loop().synchronous()).thenReturn(true);assertThrows(IllegalArgumentException.class,()->preflight.validate(config));}
    @Test void rejectsUnsupportedSeed(){when(config.seed()).thenReturn(42L);assertThrows(IllegalArgumentException.class,()->preflight.validate(config));}
}
