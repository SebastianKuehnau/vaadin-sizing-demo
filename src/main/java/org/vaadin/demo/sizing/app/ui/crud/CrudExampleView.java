package org.vaadin.demo.sizing.app.ui.crud;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.signals.Signal;
import com.vaadin.flow.signals.shared.SharedValueSignal;
import org.vaadin.demo.sizing.app.data.SamplePerson;
import org.vaadin.demo.sizing.app.service.SamplePersonService;

import java.util.Optional;


@PageTitle("Crud Example")
@Route(value = "crud-example/:samplePersonID?/:action?(edit)")
@RouteAlias("")
public class CrudExampleView extends VerticalLayout implements BeforeEnterObserver {

    static final String SAMPLEPERSON_ID = "samplePersonID";
    static final String SAMPLEPERSON_EDIT_ROUTE_TEMPLATE = "crud-example/%s/edit";

    private static final SharedValueSignal<SamplePerson> CHANGED_PERSON =
            new SharedValueSignal<>(SamplePerson.class);

    private final PersonGrid grid;
    private final SamplePersonService samplePersonService;
    private final PersonForm form;

    public CrudExampleView(CrudExampleFactory crudExampleFactory) {
        addClassNames("crud-example-view");

        samplePersonService = crudExampleFactory.createService();
        grid = crudExampleFactory.createGrid(this::clearForm);
        form = crudExampleFactory.createForm(this::personSaved, this::personDeleted, this::refreshGrid);

        // Create UI
        SplitLayout splitLayout = new SplitLayout();
        splitLayout.addToPrimary(grid);
        splitLayout.addToSecondary(form);

        add(splitLayout);

        // Runs while the view is attached; tells the other sessions about a change
        Signal.effect(this, ctx -> {
            SamplePerson changed = CHANGED_PERSON.get();
            if (changed != null && !ctx.isInitialRun() && ctx.isBackgroundChange()) {
                Notification.show(String.format("%s %s has been changed by another user",
                        changed.getFirstName(), changed.getLastName()), 3000, Notification.Position.BOTTOM_START);
            }
        });
    }

    private void personSaved(SamplePerson person) {
        grid.refreshAndSelect(person);
        CHANGED_PERSON.set(person);
    }

    private void personDeleted(SamplePerson person) {
        refreshGrid();
        CHANGED_PERSON.set(person);
    }

    private void refreshGrid() {
        grid.refreshGrid();
    }

    private void clearForm() {
        form.clearForm();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Optional<Long> samplePersonId = event.getRouteParameters().get(SAMPLEPERSON_ID).map(Long::parseLong);
        if (samplePersonId.isPresent()) {
            Optional<SamplePerson> samplePersonFromBackend = samplePersonService.get(samplePersonId.get());
            if (samplePersonFromBackend.isPresent()) {
                form.populateForm(samplePersonFromBackend.get());
            } else {
                Notification.show(
                        String.format("The requested samplePerson was not found, ID = %s", samplePersonId.get()), 3000,
                        Notification.Position.BOTTOM_START);
                // when a row is selected but the data is no longer available,
                // refresh grid
                this.refreshGrid();
                event.forwardTo(CrudExampleView.class);
            }
        }
    }
}
