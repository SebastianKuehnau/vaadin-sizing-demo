package org.vaadin.demo.sizing.app.data;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SamplePersonRepository
        extends
            JpaRepository<SamplePerson, Long>,
            JpaSpecificationExecutor<SamplePerson> {

    @EntityGraph(attributePaths = "skills")
    Page<SamplePerson> findAll(Specification<SamplePerson> spec, Pageable pageable);

    @EntityGraph(attributePaths = "skills")
    Page<SamplePerson> findAll(Pageable pageable);
}
