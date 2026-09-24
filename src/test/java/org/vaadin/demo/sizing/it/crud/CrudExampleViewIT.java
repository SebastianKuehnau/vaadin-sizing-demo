package org.vaadin.demo.sizing.it.crud;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.checkbox.testbench.CheckboxElement;
import com.vaadin.flow.component.datepicker.testbench.DatePickerElement;
import com.vaadin.flow.component.grid.testbench.GridElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import org.junit.jupiter.api.Assertions;
import org.vaadin.demo.sizing.it.AbstractIT;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CrudExampleViewIT extends AbstractIT {

    @Override
    public String getViewName() {
        return "crud-example";
    }

    @BrowserTest
    public void testGridIsDisplayed() {
        GridElement grid = $(GridElement.class).single();
        assertNotNull(grid, "Grid should be present on the page");
        assertTrue(grid.isDisplayed(), "Grid should be visible");
    }

    @BrowserTest
    public void testFormFieldsArePresent() {
        // Check if all form fields are present
        $(TextFieldElement.class).withCaption("First Name").waitForSingle();

        TextFieldElement firstName = $(TextFieldElement.class).withCaption("First Name").single();
        TextFieldElement lastName = $(TextFieldElement.class).withCaption("Last Name").single();
        TextFieldElement email = $(TextFieldElement.class).withCaption("Email").single();
        TextFieldElement phone = $(TextFieldElement.class).withCaption("Phone").single();
        DatePickerElement dateOfBirth = $(DatePickerElement.class).withCaption("Date Of Birth").single();
        TextFieldElement occupation = $(TextFieldElement.class).withCaption("Occupation").single();
        TextFieldElement role = $(TextFieldElement.class).withCaption("Role").single();
        CheckboxElement important = $(CheckboxElement.class).withCaption("Important").single();

        assertNotNull(firstName, "First Name field should be present");
        assertNotNull(lastName, "Last Name field should be present");
        assertNotNull(email, "Email field should be present");
        assertNotNull(phone, "Phone field should be present");
        assertNotNull(dateOfBirth, "Date of Birth field should be present");
        assertNotNull(occupation, "Occupation field should be present");
        assertNotNull(role, "Role field should be present");
        assertNotNull(important, "Important checkbox should be present");
    }

    @BrowserTest
    public void testButtonsArePresent() {

        $(ButtonElement.class).withCaption("Save").waitForSingle();

        ButtonElement saveButton = $(ButtonElement.class).withCaption("Save").single();
        ButtonElement cancelButton = $(ButtonElement.class).withCaption("Cancel").single();

        assertNotNull(saveButton, "Save button should be present");
        assertNotNull(cancelButton, "Cancel button should be present");
        assertTrue(saveButton.isDisplayed(), "Save button should be visible");
        assertTrue(cancelButton.isDisplayed(), "Cancel button should be visible");
    }

    @BrowserTest
    public void testCreateNewPerson() {
        // Fill out the form with new person data
        fillPersonForm("Max", "Mustermann", "max.mustermann@example.com",
                "+49123456789", LocalDate.of(1990, 5, 15),
                "Software Developer", "Senior Developer", true);

        // Click save button
        ButtonElement saveButton = $(ButtonElement.class).withCaption("Save").waitForSingle();
        saveButton.click();

        // Check for success notification
        assertNotificationShown("Data updated");
    }

    @BrowserTest
    public void testCancelForm() {
        // Fill some data
        TextFieldElement firstName = $(TextFieldElement.class).withCaption("First Name").waitForSingle();
        firstName.setValue("Test");

        // Click cancel
        ButtonElement cancelButton = $(ButtonElement.class).withCaption("Cancel").single();
        cancelButton.click();

        // Verify form is cleared
        Assertions.assertEquals("", firstName.getValue(), "Form should be cleared after cancel");
    }

    @BrowserTest
    public void testGridRowSelection() {
        GridElement grid = $(GridElement.class).waitForSingle();

        // Check if grid has any rows
        if (grid.getRowCount() > 0) {
            // Select first row
            grid.getRow(0).select();

            // Verify that form is populated (at least first name should not be empty)
            TextFieldElement firstName = $(TextFieldElement.class).withCaption("First Name").waitForSingle();
            Assertions.assertFalse(firstName.getValue().isEmpty(),
                    "Form should be populated when a grid row is selected");
        }
    }

    @BrowserTest
    public void testFormValidation() {
        // Try to save with empty required fields
        ButtonElement saveButton = $(ButtonElement.class).withCaption("Save").single();
        saveButton.click();

        // Should show validation error notification
        assertNotificationShown("Failed to update the data. Check again that all values are valid");
    }

    @BrowserTest
    public void testEmailValidation() {
        // Fill form with invalid email
        fillPersonForm("John", "Doe", "invalid-email",
                "123456789", LocalDate.of(1985, 3, 10),
                "Tester", "QA", false);

        ButtonElement saveButton = $(ButtonElement.class).withCaption("Save").single();
        saveButton.click();

        // Should show validation error
        assertNotificationShown("Failed to update the data. Check again that all values are valid");
    }

    @BrowserTest
    public void testGridColumns() {
        GridElement grid = $(GridElement.class).single();

        // Verify that all expected columns are present
        assertTrue(grid.getColumn("First Name").getHeaderCell().isDisplayed(), "First name column should be visible");
        assertTrue(grid.getColumn("Last Name").getHeaderCell().isDisplayed(), "Last name column should be visible");
        assertTrue(grid.getColumn("Email").getHeaderCell().isDisplayed(), "Email column should be visible");
        assertTrue(grid.getColumn("Phone").getHeaderCell().isDisplayed(), "Phone column should be visible");
        assertTrue(grid.getColumn("Date Of Birth").getHeaderCell().isDisplayed(), "Date of birth column should be visible");
        assertTrue(grid.getColumn("Occupation").getHeaderCell().isDisplayed(), "Occupation column should be visible");
        assertTrue(grid.getColumn("Role").getHeaderCell().isDisplayed(), "Role column should be visible");
        assertTrue(grid.getColumn("Important").getHeaderCell().isDisplayed(), "Important column should be visible");
    }

    @BrowserTest
    public void testEditExistingPerson() {
        GridElement grid = $(GridElement.class).single();

        if (grid.getRowCount() > 0) {
            // Select and edit first row
            grid.getRow(0).select();

            // Modify the first name
            TextFieldElement firstName = $(TextFieldElement.class).withCaption("First Name").waitForSingle();
            String originalName = firstName.getValue();
            firstName.setValue(originalName + " Modified");

            // Save changes
            ButtonElement saveButton = $(ButtonElement.class).withCaption("Save").single();
            saveButton.click();

            // Verify success notification
            assertNotificationShown("Data updated");
        }
    }

    @BrowserTest
    public void testImportantCheckbox() {
        CheckboxElement importantCheckbox = $(CheckboxElement.class).withCaption("Important").single();

        // Test checking/unchecking
        importantCheckbox.setChecked(true);
        assertTrue(importantCheckbox.isChecked(), "Checkbox should be checked");

        importantCheckbox.setChecked(false);
        Assertions.assertFalse(importantCheckbox.isChecked(), "Checkbox should be unchecked");
    }

    @BrowserTest
    public void testDatePicker() {
        DatePickerElement datePicker = $(DatePickerElement.class).withCaption("Date Of Birth").single();
        LocalDate testDate = LocalDate.of(1995, 8, 20);

        datePicker.setDate(testDate);
        Assertions.assertEquals(testDate, datePicker.getDate(), "Date should be set correctly");
    }

    // Helper methods
    private void fillPersonForm(String firstName, String lastName, String email,
                                String phone, LocalDate dateOfBirth, String occupation,
                                String role, boolean important) {
        $(TextFieldElement.class).withCaption("First Name").waitForSingle();

        $(TextFieldElement.class).withCaption("First Name").single().setValue(firstName);
        $(TextFieldElement.class).withCaption("Last Name").single().setValue(lastName);
        $(TextFieldElement.class).withCaption("Email").single().setValue(email);
        $(TextFieldElement.class).withCaption("Phone").single().setValue(phone);
        $(DatePickerElement.class).withCaption("Date Of Birth").single().setDate(dateOfBirth);
        $(TextFieldElement.class).withCaption("Occupation").single().setValue(occupation);
        $(TextFieldElement.class).withCaption("Role").single().setValue(role);
        $(CheckboxElement.class).withCaption("Important").single().setChecked(important);
    }

    private void assertNotificationShown(String expectedText) {
        try {
            NotificationElement notification = $(NotificationElement.class).waitForSingle();
            assertNotNull(notification, "Notification should be shown");
            assertTrue(notification.getText().contains(expectedText),
                    "Notification should contain expected text: " + expectedText);
        } catch (Exception e) {
            Assertions.fail("Expected notification with text '" + expectedText + "' was not shown");
        }
    }
}
