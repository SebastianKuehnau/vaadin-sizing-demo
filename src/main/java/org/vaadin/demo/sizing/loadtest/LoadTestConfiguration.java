package org.vaadin.demo.sizing.loadtest;

import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.shared.ui.Transport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Settings for the {@code loadtest} profile.
 */
@Configuration
@Profile("loadtest")
public class LoadTestConfiguration {

    /**
     * Every k6 iteration starts a new session and abandons it afterwards. A short timeout keeps the
     * abandoned sessions from dominating the memory figures; an iteration takes well under 30 s of
     * idle time. {@code server.servlet.session.timeout} cannot go below one minute with Tomcat.
     */
    static final int SESSION_TIMEOUT_SECONDS = 30;

    /**
     * The recording proxy of the TestBench load test tooling cannot capture WebSocket traffic, so
     * push falls back to long polling, which consists of plain HTTP requests that k6 can replay.
     */
    @Bean
    VaadinServiceInitListener loadTestServiceInitListener() {
        return event -> {
            event.getSource().addUIInitListener(
                    uiEvent -> uiEvent.getUI().getPushConfiguration().setTransport(Transport.LONG_POLLING));
            event.getSource().addSessionInitListener(
                    sessionEvent -> sessionEvent.getSession().getSession()
                            .setMaxInactiveInterval(SESSION_TIMEOUT_SECONDS));
        };
    }
}
