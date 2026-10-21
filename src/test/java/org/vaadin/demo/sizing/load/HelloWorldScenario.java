package org.vaadin.demo.sizing.load;

import com.vaadin.flow.component.button.testbench.ButtonElement;
import com.vaadin.flow.component.notification.testbench.NotificationElement;
import com.vaadin.flow.component.textfield.testbench.TextFieldElement;
import com.vaadin.testbench.BrowserTest;
import com.vaadin.testbench.loadtest.LoadTestItHelper;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.WebElement;
import org.vaadin.demo.sizing.it.AbstractIT;

import java.util.List;
import java.util.Random;

/**
 * End-to-end scenario of one user in one browser tab: opens the Hello World view,
 * says hello with an empty name field and then with a random name.
 * <p>
 * The name is written to the shared signal of the view, so in a load test every
 * virtual user also gets notifications about the names of the others. The scenario
 * only waits for its own greetings and ignores these notifications.
 * <p>
 * Like {@link EditPersonScenario}, it can be recorded with {@code loadtest:record}
 * as a k6 script when it is added to the test classes of the converter plugin.
 */
public class HelloWorldScenario extends AbstractIT {

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
        return "hello-world";
    }

    @BrowserTest
    public void sayHelloWithEmptyAndFilledName() {
        System.out.println(getClass().getSimpleName() + " random seed: " + seed);

        sayHelloWithEmptyName();
        sayHelloWithName();
    }

    private void sayHelloWithEmptyName() {
        System.out.println("Saying hello without a name");

        nameField().setValue("");
        clickAndExpectNotification(sayHelloButton(), "Hello stranger");
    }

    private void sayHelloWithName() {
        var name = "Load" + randomDigits(8);
        System.out.println("Saying hello as " + name);

        nameField().setValue(name);
        clickAndExpectNotification(sayHelloButton(), "Hello " + name);
    }

    /**
     * Clicks the element and waits for a new notification with the expected
     * text. Notifications of previous actions, and about names entered by other
     * users, may be open as well.
     */
    private void clickAndExpectNotification(WebElement element, String expectedText) {
        var previousNotifications = $(NotificationElement.class).all();
        element.click();

        waitUntilTrue(() -> $(NotificationElement.class).all().stream()
                        .filter(n -> !previousNotifications.contains(n))
                        .anyMatch(n -> n.getText().equals(expectedText)),
                "Notification '" + expectedText + "' should be shown");
    }

    private TextFieldElement nameField() {
        return $(TextFieldElement.class).withCaption("Your name").waitForSingle();
    }

    private ButtonElement sayHelloButton() {
        return $(ButtonElement.class).withCaption("Say hello").waitForSingle();
    }

    private String randomDigits(int length) {
        var digits = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.toString();
    }
}
