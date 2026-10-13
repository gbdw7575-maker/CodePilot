package com.dsh.orchestration.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local capability tests. JDBC substitutes do not claim durable PostgreSQL acceptance. */
class TaskCheckControllerFenceTest {
    JdbcTemplate jdbc;
    TransactionTemplate writes;
    TaskCheckControllerFence service;
    @BeforeEach void setup(){
        jdbc=mock(JdbcTemplate.class);writes=mock(TransactionTemplate.class);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        doAnswer(call->{Consumer<org.springframework.transaction.TransactionStatus> action=call.getArgument(0);
            action.accept(mock(org.springframework.transaction.TransactionStatus.class));return null;}).when(writes).executeWithoutResult(any());
        service=new TaskCheckControllerFence(jdbc,writes);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    TaskCheckControllerFence.Gate gate(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{return service.register("exec","root","attempt",2);}
        finally{TransactionSynchronizationManager.clear();}
    }
    @Test void returnedPermitClosesPermanentlyBeforeDurableWrite(){
        var gate=gate();var permit=gate.enter();permit.close();permit.close();service.quiesce(gate);
        assertThrows(IllegalStateException.class,gate::enter);verify(writes).executeWithoutResult(any());
    }
    @Test void outstandingCallBlocksWriteAndAdmissionStaysClosed(){
        var gate=gate();var permit=gate.enter();assertThrows(IllegalStateException.class,()->service.quiesce(gate));
        verifyNoInteractions(writes);assertThrows(IllegalStateException.class,gate::enter);
        permit.close();service.quiesce(gate);verify(writes).executeWithoutResult(any());
    }
    @Test void nestedCallsCannotAcquireAnotherPermit(){
        var gate=gate();try(var first=gate.enter()){assertThrows(IllegalStateException.class,gate::enter);}
        try(var second=gate.enter()){}service.quiesce(gate);
    }
    @ParameterizedTest @ValueSource(strings={"enter","return","quiesce"})
    void anotherThreadCannotUseOrReturnCapability(String action)throws Exception{
        var gate=gate();var permit=gate.enter();var pool=Executors.newSingleThreadExecutor();
        try{assertTrue(pool.submit(()->{
            assertThrows(IllegalStateException.class,()->{switch(action){case "enter"->gate.enter();case "return"->permit.close();default->service.quiesce(gate);}});
            return true;
        }).get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        permit.close();service.quiesce(gate);
    }
    @Test void restartedControllerCannotAdoptAnOldCapability(){
        var gate=gate();var restarted=new TaskCheckControllerFence(jdbc,writes);
        assertThrows(IllegalStateException.class,()->restarted.quiesce(gate));verifyNoInteractions(writes);
        service.quiesce(gate);
    }
    @Test void missingCapabilityCannotBeSynthesizedFromDatabase(){assertThrows(IllegalStateException.class,()->service.quiesce(null));verifyNoInteractions(jdbc,writes);}
    @Test void registrationRequiresReservationTransaction(){assertThrows(IllegalStateException.class,()->service.register("e","r","a",2));verifyNoInteractions(jdbc);}
    @Test void registrationRefusesReadOnlyTransaction(){TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class,()->service.register("e","r","a",2));verifyNoInteractions(jdbc);}
    @Test void quiescenceRefusesAnEnclosingTransaction(){var gate=gate();TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class,()->service.quiesce(gate));verifyNoInteractions(writes);}
    @Test void failedReservationDoesNotReturnLaunchCapability(){when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,this::gate);verifyNoInteractions(writes);}
    @Test void failedQuiescenceWriteDoesNotReopenAdmission(){var gate=gate();when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->service.quiesce(gate));assertThrows(IllegalStateException.class,gate::enter);}
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"../root","a b","汉字"})
    void malformedBindingCannotRegister(String bad){TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class,()->service.register("exec",bad,"attempt",2));verifyNoInteractions(jdbc);}
    @Test void zeroFenceCannotRegister(){TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class,()->service.register("exec","root","attempt",0));verifyNoInteractions(jdbc);}
}
