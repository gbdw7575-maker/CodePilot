package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {

    java.util.Optional<ArtifactEntity> findFirstByRunIdAndNameAndSha256(String runId, String name, String sha256);

    java.util.Optional<ArtifactEntity> findFirstByRunIdAndNameStartingWithOrderByNameDesc(
            String runId, String prefix);

    @Modifying
    @Query("delete from ArtifactEntity a where a.runId = :runId")
    void deleteByRunId(@Param("runId") String runId);
}
