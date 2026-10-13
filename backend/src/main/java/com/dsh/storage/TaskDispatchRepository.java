package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskDispatchRepository extends JpaRepository<TaskDispatchEntity, String> {

    /** 幂等范围：(rootRunId, requestKey)。 */
    Optional<TaskDispatchEntity> findByRootRunIdAndRequestKey(String rootRunId, String requestKey);
}
