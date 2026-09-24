package org.vaadin.demo.sizing.app.ui.crud;

import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;
import org.springframework.stereotype.Component;
import org.vaadin.demo.sizing.app.data.SamplePerson;
import org.vaadin.demo.sizing.app.service.SamplePersonService;

@Component
class CrudExampleFactory {

    private final SamplePersonService samplePersonService;

    public CrudExampleFactory(SamplePersonService samplePersonService) {
        this.samplePersonService = samplePersonService;
    }

    PersonForm createForm(SerializableConsumer<SamplePerson> onSaved, SerializableRunnable onDeleted,
                          SerializableRunnable onCancel) {
        return new PersonForm(samplePersonService, onSaved, onDeleted, onCancel);
    }

    SamplePersonService createService() {
        return samplePersonService;
    }

    public PersonGrid createGrid(SerializableRunnable clearForm) {
        return new PersonGrid(samplePersonService, clearForm);
    }
}
