package org.vaadin.demo.sizing.loadtest;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Actuator endpoint {@code /actuator/sessions} on the management port, for the session size
 * measurement (see loadtest/measure-session-size.sh): {@code DELETE} invalidates all HTTP sessions,
 * which also destroys their Vaadin sessions and UIs, so the heap can be measured without sessions.
 * <p>
 * Only active in builds with the Maven profile {@code loadtest}, which adds
 * testbench-loadtest-support to the app.
 */
@Component
@Endpoint(id = "sessions")
@ConditionalOnClass(name = "com.vaadin.testbench.loadtest.LoadTestServiceInitListener")
public class SessionsEndpoint implements HttpSessionListener {

    private final Set<HttpSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        sessions.add(event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        sessions.remove(event.getSession());
    }

    @ReadOperation
    public Map<String, Integer> count() {
        return Map.of("sessions", sessions.size());
    }

    @DeleteOperation
    public Map<String, Integer> invalidateAll() {
        int invalidated = 0;
        for (HttpSession session : List.copyOf(sessions)) {
            try {
                session.invalidate(); // calls sessionDestroyed, which removes it from the set
                invalidated++;
            } catch (IllegalStateException alreadyInvalidated) {
                sessions.remove(session);
            }
        }
        return Map.of("invalidated", invalidated);
    }
}
