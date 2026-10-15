package org.vaadin.demo.sizing.load;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.checkbox.testbench.CheckboxElement;
import com.vaadin.flow.component.combobox.testbench.MultiSelectComboBoxElement;
import com.vaadin.flow.component.confirmdialog.testbench.ConfirmDialogElement;
import com.vaadin.flow.component.datepicker.testbench.DatePickerElement;
import com.vaadin.flow.component.grid.testbench.GridElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import com.vaadin.testbench.loadtest.LoadTestItHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.WebElement;
import org.vaadin.demo.sizing.it.AbstractIT;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * End-to-end scenario of one user in one browser tab: opens the CRUD view,
 * scrolls to the middle of the grid, creates a new person, changes a random property of it (a text field or its skills) and deletes it again.
 * <p>
 * The scenario only works on the person it created itself and does not click
 * on grid rows, so it can be replayed by many virtual users in parallel. * <p>
 * It is also the basis of the k6 load test: {@code loadtest:record} runs it with
 * {@code -Dk6.proxy.host}, and the browser traffic captured by the proxy becomes
 * {@code src/test/k6/recordings/edit-person.js} (see README.md).
 */
public class EditPersonScenario extends AbstractIT {

    /**
     * A property of the person that is editable in the form as a text field.
     */
    private record EditableProperty(String caption, Function<String, String> newValue) {
    }

    private static final List<EditableProperty> EDITABLE_PROPERTIES = List.of(
            new EditableProperty("First Name", suffix -> "Firstname" + suffix),
            new EditableProperty("Last Name", suffix -> "Lastname" + suffix),
            new EditableProperty("Email", suffix -> "testbench." + suffix + "@example.com"),
            new EditableProperty("Phone", suffix -> "+49 " + suffix),
            new EditableProperty("Occupation", suffix -> "Occupation " + suffix),
            new EditableProperty("Role", suffix -> "Role " + suffix));

    /**
     * Skills that exist in the sample data and are offered by the Skills field.
     */
    private static final List<String> SKILLS = List.of("Java", "Spring", "AWS", "Kubernetes");

    private final long seed = System.currentTimeMillis();
    private final Random random = new Random(seed);

    /**
     * Routes the browser through the recording proxy when {@code k6.proxy.host}
     * is set (by {@code loadtest:record}); otherwise the driver stays as it is.
     */
    @Override
    @BeforeEach
    public void open() {
        setDriver(LoadTestItHelper.setupProxy(getDriver()));
        super.open();
    }

    @Override
    public String getViewName() {
        return "crud-example";
    }

    @BrowserTest
    public void createEditAndDeletePerson() {
        System.out.println(getClass().getSimpleName() + " random seed: " + seed);

        scrollToMiddleOfGrid();
        createPerson();
        editRandomProperty();
        deletePerson();
    }

    private void scrollToMiddleOfGrid() {
        GridElement grid = $(GridElement.class).waitForFirst();
        int middleRow = grid.getRowCount() / 2;
        System.out.println("Scrolling grid to row " + middleRow);

        grid.scrollToRow(middleRow);

        waitUntilTrue(() -> !grid.getCell(middleRow, 0).getText().isEmpty(),
                "Row " + middleRow + " should be loaded after scrolling");
        Assertions.assertTrue(grid.getFirstVisibleRowIndex() <= middleRow
                        && middleRow <= grid.getLastVisibleRowIndex(),
                "Row " + middleRow + " should be visible after scrolling");
    }

    private void createPerson() {
        String suffix = randomDigits(8);
        String firstName = "Load" + suffix;
        System.out.println("Creating person " + firstName);

        field("First Name").setValue(firstName);
        field("Last Name").setValue("Test" + suffix);
        field("Email").setValue("load." + suffix + "@example.com");
        field("Phone").setValue("+49 " + suffix);
        $(DatePickerElement.class).withCaption("Date Of Birth").single()
                .setDate(LocalDate.of(1950 + random.nextInt(50), 1 + random.nextInt(12), 1 + random.nextInt(28)));
        field("Occupation").setValue("Occupation " + suffix);
        field("Role").setValue("Role " + suffix);
        $(CheckboxElement.class).withCaption("Important").single().setChecked(random.nextBoolean());
        Set<String> skills = assignRandomSkills();
        System.out.println("Skills: " + skills);

        clickAndExpectNotification(button("Save"), "Data updated");

        // The saved person stays in the form
        waitUntil(driver -> driver.getCurrentUrl().endsWith("/edit"));
        Assertions.assertEquals(firstName, field("First Name").getValue(), "Saved person should stay in the form");
        Assertions.assertTrue(button("Delete").isEnabled(), "Delete should be enabled for the saved person");
        Assertions.assertEquals(skills, skillsField().getSelectedTexts().stream().collect(Collectors.toSet()),
                "Saved person should keep its skills");
    }

    private void editRandomProperty() {
        // One more choice than text fields: the skills
        int choice = random.nextInt(EDITABLE_PROPERTIES.size() + 1);
        if (choice == EDITABLE_PROPERTIES.size()) {
            editSkills();
            return;
        }
        EditableProperty property = EDITABLE_PROPERTIES.get(choice);
        TextFieldElement field = field(property.caption());
        String changedValue = property.newValue().apply(randomDigits(8));
        System.out.printf("Changing '%s': '%s' -> '%s'%n", property.caption(), field.getValue(), changedValue);

        field.setValue(changedValue);
        clickAndExpectNotification(button("Save"), "Data updated");

        Assertions.assertEquals(changedValue, field(property.caption()).getValue(),
                "Form should show the changed " + property.caption());
    }

    private void editSkills() {
        Set<String> skills = assignRandomSkills();
        System.out.println("Changing skills to " + skills);
        clickAndExpectNotification(button("Save"), "Data updated");

        Assertions.assertEquals(skills, skillsField().getSelectedTexts().stream().collect(Collectors.toSet()),
                "Form should show the changed skills");
    }

    /**
     * Gives the person in the form 1 to 3 random skills from {@link #SKILLS}: skills that are
     * not part of the new selection are removed, missing ones are added. Does not save.
     *
     * @return the skills the person has now
     */
    private Set<String> assignRandomSkills() {
        MultiSelectComboBoxElement field = skillsField();
        List<String> shuffled = new ArrayList<>(SKILLS);
        Collections.shuffle(shuffled, random);
        Set<String> target = new HashSet<>(shuffled.subList(0, 1 + random.nextInt(3)));

        Set<String> current = new HashSet<>(field.getSelectedTexts());
        current.stream().filter(skill -> !target.contains(skill)).forEach(field::deselectByText);
        target.stream().filter(skill -> !current.contains(skill)).forEach(field::selectByText);
        return target;
    }

    private MultiSelectComboBoxElement skillsField() {
        return $(MultiSelectComboBoxElement.class).withCaption("Skills").waitForFirst();
    }

    private void deletePerson() {
        button("Delete").click();
        ConfirmDialogElement dialog = $(ConfirmDialogElement.class).waitForSingle();
        clickAndExpectNotification(dialog.getConfirmButton(), "Person deleted");

        waitUntilTrue(() -> field("First Name").getValue().isEmpty(), "Form should be cleared after delete");
        waitUntilTrue(() -> !button("Delete").isEnabled(), "Delete should be disabled after delete");
    }

    /**
     * Clicks the element and waits for a new notification with the expected
     * text. Notifications of previous actions may still be open.
     */
    private void clickAndExpectNotification(WebElement element, String expectedText) {
        List<NotificationElement> previousNotifications = $(NotificationElement.class).all();
        element.click();

        NotificationElement notification = waitUntil(driver -> $(NotificationElement.class).all().stream()
                .filter(n -> !previousNotifications.contains(n))
                .findFirst()
                .orElse(null));
        Assertions.assertEquals(expectedText, notification.getText());
    }

    private TextFieldElement field(String caption) {
        return $(TextFieldElement.class).withCaption(caption).waitForSingle();
    }

    private ButtonElement button(String caption) {
        return $(ButtonElement.class).withCaption(caption).waitForSingle();
    }

    private String randomDigits(int length) {
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.toString();
    }
}
