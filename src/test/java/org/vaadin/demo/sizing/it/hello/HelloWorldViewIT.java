package org.vaadin.demo.sizing.it.hello;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.Keys;
import org.vaadin.demo.sizing.it.AbstractIT;

public class HelloWorldViewIT extends AbstractIT {

    @Override
    public String getViewName() {
        return "";
    }

    @BrowserTest
    public void clickingButtonShowsNotification() {
        Assertions.assertFalse($(NotificationElement.class).exists());
        $(ButtonElement.class).waitForSingle().click();
        Assertions.assertTrue($(NotificationElement.class).exists());
    }

    @BrowserTest
    public void clickingButtonTwiceShowsTwoNotifications() {
        Assertions.assertFalse($(NotificationElement.class).exists());
        ButtonElement button = $(ButtonElement.class).waitForSingle();
        button.click();
        button.click();
        waitUntil(driver -> $(NotificationElement.class).all().size() == 2);
        Assertions.assertEquals(2, $(NotificationElement.class).all().size());
    }

    @BrowserTest
    public void testClickButtonShowsHelloAnonymousUserNotificationWhenUserNameIsEmpty() {
        ButtonElement button = $(ButtonElement.class).waitForSingle();
        button.click();
        NotificationElement notificationElement = $(NotificationElement.class).waitForSingle();
        Assertions.assertEquals("Hello", notificationElement.getText());
    }

    @BrowserTest
    public void testClickButtonShowsHelloUserNotificationWhenUserIsNotEmpty() {
        TextFieldElement textField = $(TextFieldElement.class).waitForSingle();
        textField.setValue("Vaadiner");
        $(ButtonElement.class).waitForSingle().click();
        NotificationElement notificationElement = $(NotificationElement.class).waitForSingle();
        Assertions.assertEquals("Hello Vaadiner", notificationElement.getText());
    }

    @BrowserTest
    public void testEnterShowsHelloUserNotificationWhenUserIsNotEmpty() {
        TextFieldElement textField = $(TextFieldElement.class).waitForSingle();
        textField.setValue("Vaadiner");
        textField.sendKeys(Keys.ENTER);
        NotificationElement notificationElement = $(NotificationElement.class).waitForSingle();
        Assertions.assertEquals("Hello Vaadiner", notificationElement.getText());
    }
}
