package org.vaadin.demo.sizing.app.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.vaadin.demo.sizing.app.data.SamplePerson;
import org.vaadin.demo.sizing.app.data.SamplePersonRepository;
import org.vaadin.demo.sizing.app.data.SkillRepository;

@Service
public class SamplePersonService {

    private final SamplePersonRepository repository;
    private final SkillRepository skillRepository;

    public SamplePersonService(SamplePersonRepository repository, SkillRepository skillRepository) {
        this.repository = repository;
        this.skillRepository = skillRepository;
    }

    public Optional<SamplePerson> get(Long id) {
        return repository.findWithSkillsById(id);
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

    public List<String> skillNames() {
        return skillRepository.findDistinctNames();
    }

    public int count() {
        return (int) repository.count();
    }

}
