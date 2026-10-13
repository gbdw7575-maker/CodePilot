package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    @Query("select p from ProjectEntity p order by p.createdAt asc, p.id asc")
    List<ProjectEntity> listOrdered();
}
