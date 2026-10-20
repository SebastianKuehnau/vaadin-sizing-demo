package org.vaadin.demo.sizing.app.ui.crud;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.BeanValidationBinder;
import com.vaadin.flow.data.binder.ValidationException;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.vaadin.demo.sizing.app.data.SamplePerson;
import org.vaadin.demo.sizing.app.data.Skill;
import org.vaadin.demo.sizing.app.service.SamplePersonService;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class PersonForm extends Div {

    private final TextField firstName = new TextField("First Name");
    private final TextField lastName = new TextField("Last Name");
    private final TextField email = new TextField("Email");
    private final TextField phone = new TextField("Phone");
    private final DatePicker dateOfBirth = new DatePicker("Date Of Birth");
    private final TextField occupation = new TextField("Occupation");
    private final TextField role = new TextField("Role");
    private final Checkbox important = new Checkbox("Important");
    private final MultiSelectComboBox<String> skillNames = new MultiSelectComboBox<>("Skills");

    private final Button cancel = new Button("Cancel");
    private final Button save = new Button("Save");
    private final Button delete = new Button("Delete");

    private final SamplePersonService samplePersonService;
    private final SerializableConsumer<SamplePerson> onSaved;
    private final SerializableRunnable onDeleted;
    private final SerializableRunnable onCancel;
    private final BeanValidationBinder<SamplePerson> binder;
    private SamplePerson samplePerson;

    PersonForm(SamplePersonService samplePersonService, SerializableConsumer<SamplePerson> onSaved,
               SerializableRunnable onDeleted, SerializableRunnable onCancel) {
        this.samplePersonService = samplePersonService;
        this.onSaved = onSaved;
        this.onDeleted = onDeleted;
        this.onCancel = onCancel;
        setClassName("editor-layout");

        add(createFormLayout());
        add(createButtonLayout());

        // Configure Form
        binder = new BeanValidationBinder<>(SamplePerson.class);

        // Bind fields. This is where you'd define e.g. validation rules
        binder.bindInstanceFields(this);
        binder.forField(skillNames).bind(PersonForm::getSkillNames, PersonForm::setSkillNames);
        refreshSkillSuggestions();

        save.addClickListener(this::clickSaveButton);
        cancel.addClickListener(this::clickCancelButton);
        delete.addClickListener(this::clickDeleteButton);
        delete.setEnabled(false);
    }

    private void clickCancelButton(ClickEvent<Button> buttonClickEvent) {
        clearForm();
        onCancel.run();
    }

    private void clickDeleteButton(ClickEvent<Button> buttonClickEvent) {
        SamplePerson personToDelete = this.samplePerson;
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete person");
        dialog.setText(String.format("Do you really want to delete %s %s?",
                personToDelete.getFirstName(), personToDelete.getLastName()));
        dialog.setCancelable(true);
        dialog.setConfirmText("Delete");
        dialog.setConfirmButtonTheme("danger primary");
        dialog.addConfirmListener(event -> {
            samplePersonService.delete(personToDelete.getId());
            clearForm();
            onDeleted.run();
            Notification.show("Person deleted");
        });
        dialog.open();
    }

    private void clickSaveButton(ClickEvent<Button> buttonClickEvent) {
        try {
            if (this.samplePerson == null) {
                this.samplePerson = new SamplePerson();
            }
            binder.writeBean(this.samplePerson);
            // Keep the saved person (with its new id and version) in the form
            populateForm(this.samplePersonService.save(this.samplePerson));
            refreshSkillSuggestions();
            onSaved.accept(this.samplePerson);
            Notification.show("Data updated");
        } catch (ObjectOptimisticLockingFailureException exception) {
            Notification n = Notification.show(
                    "Error updating the data. Somebody else has updated the record while you were making changes.");
            n.setPosition(Notification.Position.MIDDLE);
            n.addThemeVariants(NotificationVariant.ERROR);
        } catch (ValidationException validationException) {
            Notification.show("Failed to update the data. Check again that all values are valid");
        }
    }

    private Div createFormLayout() {

        firstName.setRequired(true);
        skillNames.setAllowCustomValue(true);
        skillNames.addCustomValueSetListener(e -> {
            String name = e.getDetail().trim();
            if (!name.isEmpty()) {
                skillNames.setValue(Stream.concat(skillNames.getValue().stream(), Stream.of(name))
                        .collect(Collectors.toSet()));
            }
        });
        lastName.setId("lastname-field");

        Div editorDiv = new Div();
        editorDiv.add(
                new FormLayout(
                        firstName, lastName, email, phone, dateOfBirth,
                        occupation, role, important, skillNames));

        editorDiv.setClassName("editor");

        return editorDiv;
    }

    private HorizontalLayout createButtonLayout() {
        HorizontalLayout buttonLayout = new HorizontalLayout();
        buttonLayout.setClassName("button-layout");
        cancel.addThemeVariants(ButtonVariant.TERTIARY);
        save.addThemeVariants(ButtonVariant.TERTIARY);

        delete.addThemeVariants(ButtonVariant.ERROR);

        save.setId("save-button");
        cancel.setId("cancel-button");
        delete.setId("delete-button");

        buttonLayout.add(save, cancel, delete);
        return buttonLayout;
    }

    private void refreshSkillSuggestions() {
        Set<String> items = new LinkedHashSet<>(samplePersonService.skillNames());
        items.addAll(skillNames.getValue());
        skillNames.setItems(items);
    }

    private static Set<String> getSkillNames(SamplePerson person) {
        return person.getSkills().stream().map(Skill::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void setSkillNames(SamplePerson person, Set<String> names) {
        person.getSkills().removeIf(skill -> !names.contains(skill.getName()));
        Set<String> existing = getSkillNames(person);
        for (String name : names) {
            if (!existing.contains(name)) {
                Skill skill = new Skill();
                skill.setName(name);
                skill.setPerson(person);
                person.getSkills().add(skill);
            }
        }
    }

    void clearForm() {
        populateForm(null);
    }

    void populateForm(SamplePerson value) {
        this.samplePerson = value;
        binder.readBean(this.samplePerson);
        delete.setEnabled(value != null && value.getId() != null);
    }

}
