package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 输入快照仓储（MA-04）。 */
public interface InputSnapshotRepository extends JpaRepository<InputSnapshotEntity, String> {

    Optional<InputSnapshotEntity> findFirstByRunIdOrderByCreatedAtDesc(String runId);
}
