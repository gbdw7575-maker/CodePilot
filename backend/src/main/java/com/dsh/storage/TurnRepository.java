package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TurnRepository extends JpaRepository<TurnEntity, String> {

    List<TurnEntity> findByThreadId(String threadId);
}
