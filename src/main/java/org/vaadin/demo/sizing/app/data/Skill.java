package org.vaadin.demo.sizing.app.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;

@Entity
public class Skill extends AbstractEntity {

    private String name;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    private SamplePerson person;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SamplePerson getPerson() {
        return person;
    }

    public void setPerson(SamplePerson person) {
        this.person = person;
    }
}
