package org.vaadin.demo.sizing.load;

import com.vaadin.testbench.loadtest.LoadTestItHelper;
import org.junit.jupiter.api.BeforeEach;

/**
 * {@link EditPersonScenario} for {@code loadtest:record}: the browser is routed
 * through the recording proxy, and the captured traffic becomes a k6 script
 * ({@code src/test/k6/recordings/edit-person.js}).
 * <p>
 * Only compiled with the {@code loadtest} Maven profile, see README.md, section "Load tests".
 */
public class EditPersonIT extends EditPersonScenario {

    @Override
    @BeforeEach
    public void open() {
        setDriver(LoadTestItHelper.openWithProxy(getDriver(),
                LoadTestItHelper.getRootURL() + "/" + getViewName()));
        waitForWebComponentsUpgraded();
    }
}
