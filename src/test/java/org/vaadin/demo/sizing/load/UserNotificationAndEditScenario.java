package org.vaadin.demo.sizing.load;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.checkbox.testbench.CheckboxElement;
import com.vaadin.flow.component.confirmdialog.testbench.ConfirmDialogElement;
import com.vaadin.flow.component.datepicker.testbench.DatePickerElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.WebElement;
import org.vaadin.demo.sizing.it.AbstractIT;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * End-to-end scenario: the user greets themselves in the Hello World view,
 * then creates a new person in the CRUD view, changes a random property of it
 * and deletes it again.
 * <p>
 * The scenario only works on the person it created itself and does not click
 * on grid rows, so it can be replayed by many virtual users in parallel.
 */
public class UserNotificationAndEditScenario extends AbstractIT {

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

    private final long seed = System.currentTimeMillis();
    private final Random random = new Random(seed);

    @Override
    public String getViewName() {
        return "";
    }

    @BrowserTest
    public void greetUserThenCreateEditAndDeletePerson() {
        System.out.println(getClass().getSimpleName() + " random seed: " + seed);

        greetUserAndCheckNotification();

        navigateTo("crud-example");
        createPerson();
        editRandomProperty();
        deletePerson();
    }

    private void greetUserAndCheckNotification() {
        String name = "User" + randomDigits(6);

        $(TextFieldElement.class).withCaption("Your name").waitForSingle().setValue(name);
        clickAndExpectNotification($(ButtonElement.class).withCaption("Say hello").single(), "Hello " + name);
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

        clickAndExpectNotification(button("Save"), "Data updated");

        // The saved person stays in the form
        waitUntil(driver -> driver.getCurrentUrl().endsWith("/edit"));
        Assertions.assertEquals(firstName, field("First Name").getValue(), "Saved person should stay in the form");
        Assertions.assertTrue(button("Delete").isEnabled(), "Delete should be enabled for the saved person");
    }

    private void editRandomProperty() {
        EditableProperty property = EDITABLE_PROPERTIES.get(random.nextInt(EDITABLE_PROPERTIES.size()));
        TextFieldElement field = field(property.caption());
        String changedValue = property.newValue().apply(randomDigits(8));
        System.out.printf("Changing '%s': '%s' -> '%s'%n", property.caption(), field.getValue(), changedValue);

        field.setValue(changedValue);
        clickAndExpectNotification(button("Save"), "Data updated");

        Assertions.assertEquals(changedValue, field(property.caption()).getValue(),
                "Form should show the changed " + property.caption());
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
