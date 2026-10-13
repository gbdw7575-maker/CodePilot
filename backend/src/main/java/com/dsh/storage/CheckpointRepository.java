package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CheckpointRepository extends JpaRepository<CheckpointEntity, String> {

    @Modifying
    @Query("delete from CheckpointEntity c where c.runId = :runId")
    void deleteByRunId(@Param("runId") String runId);

    @Query("select c from CheckpointEntity c where c.runId = :runId order by c.createdAt desc, c.id desc")
    List<CheckpointEntity> listByRun(@Param("runId") String runId);
}
