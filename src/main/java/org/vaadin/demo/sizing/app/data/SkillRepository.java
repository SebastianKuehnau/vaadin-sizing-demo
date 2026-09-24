package org.vaadin.demo.sizing.app.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SkillRepository extends JpaRepository<Skill, Long> {

    @Query("SELECT DISTINCT s.name FROM Skill s ORDER BY s.name")
    List<String> findDistinctNames();
}
