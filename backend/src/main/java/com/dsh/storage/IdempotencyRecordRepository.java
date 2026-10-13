package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    Optional<IdempotencyRecordEntity> findByScopeKeyAndIdempotencyKey(String scopeKey, String idempotencyKey);
}
