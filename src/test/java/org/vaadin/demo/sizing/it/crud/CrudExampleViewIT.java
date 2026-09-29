package org.vaadin.demo.sizing.it.crud;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.checkbox.testbench.CheckboxElement;
import com.vaadin.flow.component.confirmdialog.testbench.ConfirmDialogElement;
import com.vaadin.flow.component.datepicker.testbench.DatePickerElement;
import com.vaadin.flow.component.grid.testbench.GridElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import org.junit.jupiter.api.Assertions;
import org.vaadin.demo.sizing.it.AbstractIT;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CrudExampleViewIT extends AbstractIT {

    private static final Pattern EDIT_URL = Pattern.compile("/crud-example/(\\d+)/edit$");

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
        ButtonElement deleteButton = $(ButtonElement.class).withCaption("Delete").single();

        assertNotNull(saveButton, "Save button should be present");
        assertNotNull(cancelButton, "Cancel button should be present");
        assertNotNull(deleteButton, "Delete button should be present");
        assertTrue(saveButton.isDisplayed(), "Save button should be visible");
        assertTrue(cancelButton.isDisplayed(), "Cancel button should be visible");
        assertTrue(deleteButton.isDisplayed(), "Delete button should be visible");
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

        // The saved person stays in the form and can be deleted
        waitForEditUrl();
        Assertions.assertEquals("Max", $(TextFieldElement.class).withCaption("First Name").single().getValue(),
                "Saved person should stay in the form");
        Assertions.assertEquals("Senior Developer", $(TextFieldElement.class).withCaption("Role").single().getValue(),
                "Saved person should stay in the form");
        assertTrue(deleteButton().isEnabled(), "Delete button should be enabled for a saved person");

        // Clean up
        deleteCurrentPerson();
    }

    @BrowserTest
    public void testNewPersonAppearsInGrid() {
        GridElement grid = $(GridElement.class).waitForSingle();
        waitUntilTrue(() -> grid.getRowCount() > 0, "Grid should load its rows");
        int rowCountBefore = grid.getRowCount();

        // Unique name, as the tests run in parallel and may add persons at the same time
        String firstName = "Grid" + System.nanoTime();
        fillPersonForm(firstName, "Newcomer", "grid.newcomer@example.com",
                "+49111222333", LocalDate.of(1992, 7, 1),
                "Tester", "QA", false);
        $(ButtonElement.class).withCaption("Save").single().click();
        assertNotificationShown("Data updated");
        waitForEditUrl();

        // New persons are appended (no sort order); the grid shows and selects it without reloading the page
        waitUntilTrue(() -> grid.getRowCount() > rowCountBefore, "Grid should contain the new row");
        int lastRow = grid.getRowCount() - 1;
        grid.scrollToRow(lastRow);
        waitUntilTrue(() -> findRow(grid, firstName, lastRow) >= 0, "New person should be shown in the grid");
        assertTrue(grid.getRow(findRow(grid, firstName, lastRow)).isSelected(), "New person should be selected in the grid");

        // Clean up
        deleteCurrentPerson();
    }

    /**
     * Returns the index of the row with the given first name among the last
     * rows up to {@code lastRow}, or -1.
     */
    private int findRow(GridElement grid, String firstName, int lastRow) {
        for (int row = lastRow; row >= Math.max(0, lastRow - 5); row--) {
            if (firstName.equals(grid.getCell(row, 0).getText())) {
                return row;
            }
        }
        return -1;
    }

    @BrowserTest
    public void testDeleteDisabledWithoutSelection() {
        assertFalse(deleteButton().isEnabled(), "Delete button should be disabled when no person is selected");
    }

    @BrowserTest
    public void testDeleteCancelKeepsPerson() {
        GridElement grid = $(GridElement.class).waitForSingle();
        grid.getRow(0).select();

        TextFieldElement firstName = $(TextFieldElement.class).withCaption("First Name").waitForSingle();
        waitUntil(driver -> !firstName.getValue().isEmpty());
        String selectedName = firstName.getValue();

        deleteButton().click();
        ConfirmDialogElement dialog = $(ConfirmDialogElement.class).waitForSingle();
        dialog.getCancelButton().click();

        waitUntil(driver -> $(ConfirmDialogElement.class).all().isEmpty());
        Assertions.assertEquals(selectedName, firstName.getValue(), "Person should stay in the form after cancelling delete");
        assertTrue(grid.getRow(0).isSelected(), "Row should stay selected after cancelling delete");
    }

    @BrowserTest
    public void testCreateAndDeletePerson() {
        fillPersonForm("Erika", "Musterfrau", "erika.musterfrau@example.com",
                "+49987654321", LocalDate.of(1985, 1, 20),
                "Tester", "QA", false);
        $(ButtonElement.class).withCaption("Save").single().click();
        assertNotificationShown("Data updated");
        long id = waitForEditUrl();

        deleteCurrentPerson();

        waitUntilTrue(() -> $(TextFieldElement.class).withCaption("First Name").single().getValue().isEmpty(),
                "Form should be cleared after delete");
        waitUntilTrue(() -> !deleteButton().isEnabled(), "Delete button should be disabled after delete");

        // The deleted person can no longer be opened
        navigateTo("crud-example/" + id + "/edit");
        assertNotificationShown("The requested samplePerson was not found, ID = " + id);
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

            // The edited person stays selected and in the form
            assertTrue(grid.getRow(0).isSelected(), "Edited row should stay selected");
            Assertions.assertEquals(originalName + " Modified", firstName.getValue(), "Form should show the changed value");
            waitUntil(driver -> (originalName + " Modified").equals(grid.getCell(0, 0).getText()));

            // Revert the change (saving again also checks that the form holds the new version)
            firstName.setValue(originalName);
            saveButton.click();
            waitUntil(driver -> originalName.equals(grid.getCell(0, 0).getText()));
            assertTrue(grid.getRow(0).isSelected(), "Edited row should stay selected");
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

    private ButtonElement deleteButton() {
        return $(ButtonElement.class).withCaption("Delete").waitForSingle();
    }

    /**
     * Deletes the person shown in the form and confirms the dialog.
     */
    private void deleteCurrentPerson() {
        deleteButton().click();
        ConfirmDialogElement dialog = $(ConfirmDialogElement.class).waitForSingle();
        assertTrue(dialog.getMessageText().startsWith("Do you really want to delete"),
                "Confirm dialog should ask before deleting");
        dialog.getConfirmButton().click();
        assertNotificationShown("Person deleted");
    }

    /**
     * Waits until the URL points to the edit route and returns the id of the person.
     */
    private long waitForEditUrl() {
        waitUntil(driver -> EDIT_URL.matcher(driver.getCurrentUrl()).find());
        Matcher matcher = EDIT_URL.matcher(getDriver().getCurrentUrl());
        assertTrue(matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    private void assertNotificationShown(String expectedText) {
        try {
            // Several notifications may be open at the same time, so look for any matching one
            waitUntil(driver -> $(NotificationElement.class).all().stream()
                    .anyMatch(n -> n.getText().contains(expectedText)));
        } catch (Exception e) {
            Assertions.fail("Expected notification with text '" + expectedText + "' was not shown");
        }
    }
}
