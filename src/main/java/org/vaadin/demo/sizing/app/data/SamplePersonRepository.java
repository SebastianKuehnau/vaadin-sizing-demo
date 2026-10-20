package org.vaadin.demo.sizing.app.data;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SamplePersonRepository
        extends
            JpaRepository<SamplePerson, Long> {

}
