package com.dsh.api;

import com.dsh.orchestration.DependencyMaterializationService;
import com.dsh.orchestration.task.TaskQueryService;
import com.dsh.storage.TaskEntity;
import com.dsh.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** One necessary boundary check: actual strong ZIP bytes, then stale upstream refusal. */
class StrongDeliveryMaterializationTest extends StrongReceiptConsumptionTest {
    @Test void actualCommittedDeliveryIsReadableByDownstreamAndDriftBlocksCollection() throws Exception {
        var f=h.fixture("slugify-semantic");h.completion.commit(h.observed(f));var c=f.confirmed().chain();
        var now=Instant.now();var downstream=new TaskEntity("query-only",c.plan(),"B",1,"consume A","[\"A\"]","[{\"taskId\":\"A\",\"output\":\"Java\"}]","Java","[\"read original bytes\"]",null,"DELEGATED",true,"DRAFT",now,now);
        var query=context.getBean(TaskQueryService.class);var refs=query.collectUpstreamArtifactRefs(c.plan(),downstream);assertFalse(refs.isEmpty());
        String child="materialize_"+UUID.randomUUID().toString().replace("-","");
        var materialized=context.getBean(DependencyMaterializationService.class).materialize(child,c.project(),refs);
        assertTrue(materialized.ok(),materialized.errorMessage());var lease=context.getBean(WorkspaceManager.class).find(child);
        assertArrayEquals(Files.readAllBytes(f.lease().dir().resolve("src/main/java/sample/Value.java")),Files.readAllBytes(lease.dir().resolve("upstream/"+f.child()+"/delivery/src/main/java/sample/Value.java")));
        jdbc.update("UPDATE task_check_completions SET receipt_digest=? WHERE attempt_id=?","a".repeat(64),f.attempt());
        assertThrows(ApiException.class,()->query.collectUpstreamArtifactRefs(c.plan(),downstream));
        context.getBean(WorkspaceManager.class).release(child);
    }
}