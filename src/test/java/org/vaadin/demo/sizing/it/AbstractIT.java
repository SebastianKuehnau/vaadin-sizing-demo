package org.vaadin.demo.sizing.it;

import com.vaadin.testbench.BrowserTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;

import java.util.function.BooleanSupplier;

/**
 * Base class for all our tests, allowing us to change the applicable driver,
 * test URL or other configurations in one place.
 */
public abstract class AbstractIT extends BrowserTestBase {

    /**
     * If running on CI, get the host name from environment variable HOSTNAME
     *
     * @return the host name
     */
    private static String getDeploymentHostname() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        }
        return "localhost";
    }

    @BeforeEach
    public void open() {
        navigateTo(getViewName());
    }

    /**
     * Opens the given view in the browser.
     *
     * @param viewName the route of the view, e.g. {@code "crud-example"}
     */
    protected void navigateTo(String viewName) {
        getDriver().get("http://" + getDeploymentHostname() + ":8080/" + viewName);
        waitForWebComponentsUpgraded();
    }

    /**
     * Waits until the view has been rendered and all Vaadin web components on
     * the page are defined, so their internal elements (e.g. the input of a
     * text field) exist before the test interacts with them.
     * <p>
     * {@code vaadin-grid-cell-content} is excluded, as it is a plain element
     * that is never registered as a custom element.
     */
    protected void waitForWebComponentsUpgraded() {
        try {
            waitUntil(driver -> (Boolean) executeScript(VAADIN_COMPONENT_TAGS_SCRIPT
                    + "return tags.length > 0 && tags.every(tag => customElements.get(tag));"));
        } catch (TimeoutException e) {
            throw new AssertionError("Vaadin web components were not loaded in the browser: "
                    + executeScript(VAADIN_COMPONENT_TAGS_SCRIPT
                    + "return tags.filter(tag => !customElements.get(tag)).join(', ');"), e);
        }
    }

    /** Collects the distinct tag names of all Vaadin components on the page into {@code tags}. */
    private static final String VAADIN_COMPONENT_TAGS_SCRIPT =
            "const tags = [...new Set([...document.querySelectorAll('*')].map(e => e.localName))]"
                    + ".filter(tag => tag.startsWith('vaadin-')"
                    + " && tag !== 'vaadin-grid-cell-content');";

    /**
     * Waits until the condition is true. Elements may be re-rendered while the
     * server response is applied, so a stale element just means "try again".
     *
     * @param condition the condition to check, looking up its elements on every call
     * @param message   the failure message if the condition never becomes true
     */
    protected void waitUntilTrue(BooleanSupplier condition, String message) {
        try {
            waitUntil(driver -> {
                try {
                    return condition.getAsBoolean();
                } catch (StaleElementReferenceException e) {
                    return false;
                }
            });
        } catch (TimeoutException e) {
            throw new AssertionError(message, e);
        }
    }

    abstract public String getViewName();
}
