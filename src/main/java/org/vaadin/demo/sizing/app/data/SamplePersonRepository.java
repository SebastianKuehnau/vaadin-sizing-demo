package org.vaadin.demo.sizing.app.data;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SamplePersonRepository
        extends
            JpaRepository<SamplePerson, Long> {

    @EntityGraph(attributePaths = "skills")
    Page<SamplePerson> findAll(Pageable pageable);
}
