package org.vaadin.demo.sizing.app.service;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.vaadin.demo.sizing.app.data.SamplePerson;
import org.vaadin.demo.sizing.app.data.SamplePersonRepository;

@Service
public class SamplePersonService {

    private final SamplePersonRepository repository;

    public SamplePersonService(SamplePersonRepository repository) {
        this.repository = repository;
    }

    public Optional<SamplePerson> get(Long id) {
        return repository.findById(id);
    }

    public SamplePerson save(SamplePerson entity) {
        return repository.save(entity);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    public Page<SamplePerson> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    public int count() {
        return (int) repository.count();
    }

}
