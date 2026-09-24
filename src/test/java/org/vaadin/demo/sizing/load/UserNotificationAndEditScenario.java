package org.vaadin.demo.sizing.load;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.grid.testbench.GridColumnElement;
import com.vaadin.flow.component.grid.testbench.GridElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import org.junit.jupiter.api.Assertions;
import org.vaadin.demo.sizing.it.AbstractIT;

import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * End-to-end scenario: the user greets themselves in the Hello World view,
 * then edits a random property of a random person in the CRUD view and
 * reverts the change again.
 */
public class UserNotificationAndEditScenario extends AbstractIT {

    /**
     * Notification.show() uses a duration of 5 seconds, so after 8 seconds
     * the notification must be gone.
     */
    private static final long NOTIFICATION_GONE_AFTER_MILLIS = 8_000;

    /**
     * A property of the person that is editable in the form and shown as
     * plain text in the grid.
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
    public void greetUserThenEditAndRevertRandomPerson() throws InterruptedException {
        System.out.println(getClass().getSimpleName() + " random seed: " + seed);

        greetUserAndCheckNotification();

        navigateTo("crud-example");
        editAndRevertRandomPerson();
    }

    private void greetUserAndCheckNotification() throws InterruptedException {
        String name = "User" + randomDigits(6);

        $(TextFieldElement.class).withCaption("Your name").waitForSingle().setValue(name);
        $(ButtonElement.class).withCaption("Say hello").single().click();

        NotificationElement notification = $(NotificationElement.class).waitForSingle();
        Assertions.assertTrue(notification.isOpen(), "Notification should be open");
        Assertions.assertEquals("Hello " + name, notification.getText());

        Thread.sleep(NOTIFICATION_GONE_AFTER_MILLIS);

        Assertions.assertTrue(
                $(NotificationElement.class).all().stream().noneMatch(NotificationElement::isOpen),
                "Notification should have disappeared after " + NOTIFICATION_GONE_AFTER_MILLIS + " ms");
    }

    private void editAndRevertRandomPerson() {
        GridElement grid = $(GridElement.class).waitForSingle();
        waitUntil(driver -> grid.getRowCount() > 0);

        int lastVisibleRow = Math.min(grid.getLastVisibleRowIndex(), grid.getRowCount() - 1);
        int row = grid.getFirstVisibleRowIndex()
                + random.nextInt(lastVisibleRow - grid.getFirstVisibleRowIndex() + 1);
        EditableProperty property = EDITABLE_PROPERTIES.get(random.nextInt(EDITABLE_PROPERTIES.size()));
        GridColumnElement column = grid.getColumn(property.caption());

        String originalValue = grid.getCell(row, column).getText();
        String changedValue = property.newValue().apply(randomDigits(8));
        System.out.printf("Editing '%s' of row %d: '%s' -> '%s'%n",
                property.caption(), row, originalValue, changedValue);

        // Change the value and check the grid
        updatePerson(grid, row, property, originalValue, changedValue);
        waitUntil(driver -> changedValue.equals(grid.getCell(row, column).getText()));
        Assertions.assertEquals(changedValue, grid.getCell(row, column).getText(),
                "Grid should show the changed " + property.caption());

        // Revert the change and check the grid again
        updatePerson(grid, row, property, changedValue, originalValue);
        waitUntil(driver -> originalValue.equals(grid.getCell(row, column).getText()));
        Assertions.assertEquals(originalValue, grid.getCell(row, column).getText(),
                "Grid should show the original " + property.caption() + " again");
    }

    private void updatePerson(GridElement grid, int row, EditableProperty property,
                              String expectedCurrentValue, String newValue) {
        grid.select(row);

        TextFieldElement field = $(TextFieldElement.class).withCaption(property.caption()).waitForSingle();
        // Wait until the form has been populated with the selected person
        waitUntil(driver -> expectedCurrentValue.equals(field.getValue()));
        field.setValue(newValue);

        // The notification of a previous save may still be open, so look for a new one
        List<NotificationElement> previousNotifications = $(NotificationElement.class).all();
        $(ButtonElement.class).withCaption("Save").single().click();

        NotificationElement notification = waitUntil(driver -> $(NotificationElement.class).all().stream()
                .filter(n -> !previousNotifications.contains(n))
                .findFirst()
                .orElse(null));
        Assertions.assertEquals("Data updated", notification.getText());
    }

    private String randomDigits(int length) {
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.toString();
    }
}
