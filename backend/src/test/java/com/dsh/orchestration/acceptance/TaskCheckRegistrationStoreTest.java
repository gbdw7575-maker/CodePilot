package com.dsh.orchestration.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG unique constraints and separate transactions; no model, HTTP confirmation or dispatch. */
@SpringBootTest(properties="dsh.orchestration.enabled=false")
class TaskCheckRegistrationStoreTest {
    @TempDir Path temp;
    @Autowired TaskCheckRegistrationStore store;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;
    TransactionTemplate tx; TaskCheckRegistrationBinding binding;
    @BeforeEach void setup() throws Exception {
        tx=new TransactionTemplate(manager);
        binding=TaskCheckRegistrationBinding.from(TaskCheckRegistrationBindingTest.fixture(temp,"plan_"+UUID.randomUUID()));
    }
    TaskCheckRegistrationStore.Stored append(TaskCheckRegistrationBinding b){return tx.execute(s->store.append(b));}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM task_check_registrations WHERE plan_id=?",Long.class,binding.planId);}
    TaskCheckRegistrationBinding changed(java.util.function.Consumer<ObjectNode> change){
        var snapshot=TaskCheckRegistrationBinding.decode(binding.canonicalSnapshot);
        try {var p=new com.fasterxml.jackson.databind.ObjectMapper().readValue(snapshot.canonicalSnapshot,com.dsh.api.dto.TaskCheckSelectionPreview.class);
            return TaskCheckRegistrationBinding.from(TaskCheckRegistrationBindingTest.changed(p,change));
        } catch(Exception error){throw new IllegalStateException(error);}
    }
    @Test void commitAndCrossInstanceReplayReturnFirstBytesIdAndTimestamp(){
        var first=append(binding);var second=tx.execute(s->new TaskCheckRegistrationStore(jdbc).append(binding));
        assertEquals(first,second);assertEquals(1,count());assertTrue(first.id().matches("checkreg_[a-f0-9]{32}"));
        assertEquals(binding.canonicalBinding,first.bindingJson());assertEquals(binding.canonicalSnapshot,first.snapshotJson());
    }
    @Test void transactionRollbackDoesNotLeaveAnApprovalOrReservation(){
        var rolledBack=tx.execute(s->{var row=store.append(binding);s.setRollbackOnly();return row;});assertEquals(0,count());
        var committed=append(binding);assertNotEquals(rolledBack.id(),committed.id());assertEquals(1,count());
    }
    @Test void noTransactionAndReadonlyTransactionRejectBeforeWriting(){
        assertThrows(IllegalStateException.class,()->store.append(binding));var read=new TransactionTemplate(manager);read.setReadOnly(true);
        assertThrows(IllegalStateException.class,()->read.execute(s->store.append(binding)));assertEquals(0,count());
    }
    @Test void sameVersionDifferentDefinitionAndRequirementsCannotOverwrite(){
        var original=append(binding);
        for(var other:List.of(changed(n->((ObjectNode)n.path("taskPreview").path("task")).put("goal","其他目标")),
                changed(n->((ObjectNode)n.path("taskPreview").path("requirements").get(0)).put("content","新增约束"))))
            assertThrows(TaskCheckRegistrationStore.BindingConflict.class,()->append(other));
        assertEquals(original,append(binding));assertEquals(1,count());
    }
    @Test void catalogImagePolicyOrVerifierDriftCannotAutomaticallyResign(){
        var original=append(binding);
        for(String field:List.of("registryDigest","imageDigest","executionPolicyDigest"))
            assertThrows(TaskCheckRegistrationStore.BindingConflict.class,()->append(changed(n->((ObjectNode)n.get("taskPreview")).put(field,"f".repeat(64)))));
        assertThrows(TaskCheckRegistrationStore.BindingConflict.class,()->append(changed(n->{
            ((ObjectNode)n.path("selectedChecks").get(0).path("verifiers").get(0)).put("sha256","f".repeat(64));
            ((ObjectNode)n.path("taskPreview").path("availableChecks").get(0).path("verifiers").get(0)).put("sha256","f".repeat(64));})));
        assertEquals(original,append(binding));
    }
    @Test void unrelatedPlanRevisionAndStatePreserveOriginalObservation(){
        var first=append(binding);var later=changed(n->((ObjectNode)n.get("taskPreview")).put("planRevision",2).put("taskState","PENDING"));
        assertEquals(first,append(later));assertEquals(1,first.observedPlanRevision());assertEquals(1,count());
    }
    @Test void newTaskVersionAndSeparateRootsUseSeparateSlots(){
        var first=append(binding);var v2=changed(n->((ObjectNode)n.path("taskPreview").path("task")).put("taskVersion",2));
        var second=append(v2);assertNotEquals(first.id(),second.id());
        for(String field:List.of("projectId","rootRunId","turnId")) assertNotEquals(first.id(),append(changed(n->((ObjectNode)n.get("taskPreview")).put(field,"other_identity"))).id());
        assertEquals(5,count());assertEquals(first,append(binding));
    }
    @Test void simultaneousSameBindingWritersConvergeAcrossStoreInstances() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(3);
        try {var futures=new ArrayList<Future<TaskCheckRegistrationStore.Stored>>();
            for(int i=0;i<3;i++)futures.add(pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return tx.execute(s->new TaskCheckRegistrationStore(jdbc).append(binding));}));
            start.countDown();var first=futures.getFirst().get(20,TimeUnit.SECONDS);
            for(var f:futures)assertEquals(first,f.get(20,TimeUnit.SECONDS));assertEquals(1,count());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void heldWinnerRollsBackAndWaitingWriterCanCommit() throws Exception {
        var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var winner=pool.submit(()->tx.execute(s->{var row=store.append(binding);inserted.countDown();
            try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}s.setRollbackOnly();return row;}));
            assertTrue(inserted.await(10,TimeUnit.SECONDS));var waiter=pool.submit(()->{started.countDown();return append(binding);});
            assertTrue(started.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiter.get(150,TimeUnit.MILLISECONDS));
            release.countDown();var rolled=winner.get(15,TimeUnit.SECONDS);var committed=waiter.get(15,TimeUnit.SECONDS);
            assertNotEquals(rolled.id(),committed.id());assertEquals(committed,append(binding));assertEquals(1,count());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void competingDifferentBindingsProduceOneWinnerAndOneConflict() throws Exception {
        var other=changed(n->((ObjectNode)n.path("taskPreview").path("task")).put("goal","另一目标"));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var results=new ArrayList<Future<Object>>();for(var b:List.of(binding,other))results.add(pool.submit(()->{
            assertTrue(start.await(10,TimeUnit.SECONDS));try{return append(b);}catch(TaskCheckRegistrationStore.BindingConflict conflict){return conflict;}}));
            start.countDown();var values=List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS));
            assertEquals(1,values.stream().filter(TaskCheckRegistrationStore.Stored.class::isInstance).count());
            assertEquals(1,values.stream().filter(TaskCheckRegistrationStore.BindingConflict.class::isInstance).count());assertEquals(1,count());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"binding_digest","binding_json","snapshot_digest","snapshot_json","observed_plan_revision","task_id"})
    void directDatabaseCorruptionIsRejectedWithoutRepair(String field){
        var first=append(binding);
        // Column names are from the fixed test allowlist, never runtime caller input.
        if(field.equals("observed_plan_revision"))jdbc.update("UPDATE task_check_registrations SET observed_plan_revision=2 WHERE id=?",first.id());
        else if(field.equals("task_id"))jdbc.update("UPDATE task_check_registrations SET task_id='B' WHERE id=?",first.id());
        else jdbc.update("UPDATE task_check_registrations SET "+field+"=? WHERE id=?",field.endsWith("digest")?"0".repeat(64):"{}",first.id());
        if(field.equals("task_id")){
            // Query the corrupted slot with its apparent logical ID; the stored snapshot still says A.
            var b=changed(n->((ObjectNode)n.path("taskPreview").path("task")).put("taskId","B"));
            assertThrows(IllegalStateException.class,()->append(b));
        } else assertThrows(IllegalStateException.class,()->append(binding));
        assertEquals(1,count());
    }
}
