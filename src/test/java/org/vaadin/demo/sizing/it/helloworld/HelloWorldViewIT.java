package org.vaadin.demo.sizing.it.helloworld;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WindowType;
import org.vaadin.demo.sizing.it.AbstractIT;

/**
 * Through the signal in HelloWorldView, the tests see the names entered in each other's sessions.
 * TestBench runs tests in parallel by default, so these run one after another.
 */
@Execution(ExecutionMode.SAME_THREAD)
public class HelloWorldViewIT extends AbstractIT {

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
        Assertions.assertEquals(2, $(NotificationElement.class).all().size());
    }

    @BrowserTest
    public void testClickButtonShowsHelloAnonymousUserNotificationWhenUserNameIsEmpty() {
        ButtonElement button = $(ButtonElement.class).waitForSingle();
        button.click();
        NotificationElement notificationElement = $(NotificationElement.class).waitForSingle();
        Assertions.assertEquals("Hello stranger", notificationElement.getText());
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

    @BrowserTest
    public void testOtherTabIsNotifiedAboutEnteredName() {
        // A second tab has its own UI, so the name reaches it as a change from outside
        String firstTab = getDriver().getWindowHandle();
        getDriver().switchTo().newWindow(WindowType.TAB);
        navigateTo(getViewName());
        String secondTab = getDriver().getWindowHandle();

        // Unique name, so a notification of an earlier name is not taken for this one
        getDriver().switchTo().window(firstTab);
        String name = "Signal" + System.nanoTime();
        String expectedText = "Another user entered the name " + name;
        $(TextFieldElement.class).waitForSingle().setValue(name);
        $(ButtonElement.class).waitForSingle().click();
        $(NotificationElement.class).waitForSingle();

        getDriver().switchTo().window(secondTab);
        waitUntil(driver -> $(NotificationElement.class).all().stream()
                .anyMatch(n -> n.getText().equals(expectedText)));
        getDriver().close();

        // The tab that entered the name is not notified
        getDriver().switchTo().window(firstTab);
        Assertions.assertFalse($(NotificationElement.class).all().stream()
                        .anyMatch(n -> n.getText().equals(expectedText)),
                "The tab that entered the name should not be notified");
    }

    @BrowserTest
    public void testEmptyNameIsNotSentToOtherTab() {
        String firstTab = getDriver().getWindowHandle();
        getDriver().switchTo().newWindow(WindowType.TAB);
        navigateTo(getViewName());
        String secondTab = getDriver().getWindowHandle();

        // The own UI greets a stranger
        getDriver().switchTo().window(firstTab);
        $(ButtonElement.class).waitForSingle().click();
        NotificationElement notificationElement = $(NotificationElement.class).waitForSingle();
        Assertions.assertEquals("Hello stranger", notificationElement.getText());

        // A name entered afterwards reaches the other tab. Changes arrive in order, so once it is
        // there, a notification about the empty name would be there too.
        String name = "Signal" + System.nanoTime();
        String expectedText = "Another user entered the name " + name;
        $(TextFieldElement.class).waitForSingle().setValue(name);
        $(ButtonElement.class).waitForSingle().click();

        getDriver().switchTo().window(secondTab);
        waitUntil(driver -> $(NotificationElement.class).all().stream()
                .anyMatch(n -> n.getText().equals(expectedText)));
        Assertions.assertEquals(1, $(NotificationElement.class).all().size(),
                "The other tab should only be notified about the entered name, not the empty one");
        getDriver().close();
        getDriver().switchTo().window(firstTab);
    }

    @Override
    public String getViewName() {
        return "hello-world";
    }
}
