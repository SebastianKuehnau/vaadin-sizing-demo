package org.vaadin.demo.sizing.app.ui.helloworld;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.signals.Signal;
import com.vaadin.flow.signals.shared.SharedValueSignal;

@PageTitle("Hello World")
@Route("hello-world")
public class HelloWorldView extends HorizontalLayout {

    public record UserName(String name, long time) {}

    private static final SharedValueSignal<UserName> ENTERED_NAME = new SharedValueSignal<>(UserName.class);

    private TextField nameField;
    private Button sayHelloButton;

    public HelloWorldView() {
        nameField = new TextField("Your name");
        sayHelloButton = new Button("Say hello");
        sayHelloButton.addThemeVariants(ButtonVariant.PRIMARY);

        sayHelloButton.addClickListener(e -> {
            var name = nameField.getValue().isBlank() ? "stranger" : nameField.getValue();
            Notification.show("Hello " + name);

            ENTERED_NAME.set(new UserName(name, System.currentTimeMillis()));
        });
        sayHelloButton.addClickShortcut(Key.ENTER);

        setVerticalComponentAlignment(Alignment.BASELINE, nameField, sayHelloButton);
        setPadding(true);
        setSizeFull();

        add(nameField, sayHelloButton);

        // Runs while the view is attached; shows the names entered in the other sessions
        Signal.effect(this, ctx -> {
            UserName entered = ENTERED_NAME.get();
            if (entered != null && !ctx.isInitialRun() && ctx.isBackgroundChange()) {
                Notification.show("Another user entered the name " + entered);
            }
        });
    }
}
