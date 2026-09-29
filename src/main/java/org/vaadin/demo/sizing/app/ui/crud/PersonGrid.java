package org.vaadin.demo.sizing.app.ui.crud;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.Uses;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.function.SerializableRunnable;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import org.springframework.data.domain.PageRequest;
import org.vaadin.demo.sizing.app.data.SamplePerson;
import org.vaadin.demo.sizing.app.service.SamplePersonService;

@Uses(Icon.class)
class PersonGrid extends Div {

    private final Grid<SamplePerson> grid = new Grid<>(SamplePerson.class, false);

    PersonGrid(SamplePersonService samplePersonService, SerializableRunnable formClean) {
        setClassName("grid-wrapper");
        add(grid);

        grid.addColumn("firstName").setAutoWidth(true);
        grid.addColumn("lastName").setAutoWidth(true);
        grid.addColumn("email").setAutoWidth(true);
        grid.addColumn("phone").setAutoWidth(true);
        grid.addColumn("dateOfBirth").setAutoWidth(true);
        grid.addColumn("occupation").setAutoWidth(true);
        grid.addColumn("role").setAutoWidth(true);
        var importantRenderer = LitRenderer.<SamplePerson>of(
                        "<vaadin-icon icon='vaadin:${item.icon}' style='color: ${item.color};'></vaadin-icon>")
                .withProperty("icon", important -> important.isImportant() ? "check" : "minus")
                .withProperty("color", important -> important.isImportant()
                        ? "var(--aura-accent-text-color)" : "var(--vaadin-text-color-disabled)");
        grid.addColumn(importantRenderer).setHeader("Important").setAutoWidth(true);

        grid.setItems(
                query -> samplePersonService.list(
                                PageRequest.of(query.getPage(), query.getPageSize(), VaadinSpringDataHelpers.toSpringDataSort(query)))
                        .stream(),
                query -> samplePersonService.count());

        // when a row is selected or deselected, populate form
        grid.asSingleSelect().addValueChangeListener(event -> {
            if (event.getValue() != null) {
                UI.getCurrent().navigate(String.format(CrudExampleView.SAMPLEPERSON_EDIT_ROUTE_TEMPLATE, event.getValue().getId()));
            } else {
                formClean.run();
                UI.getCurrent().navigate(CrudExampleView.class);
            }
        });
    }


    void refreshGrid() {
        grid.select(null);
        grid.getDataProvider().refreshAll();
    }

    /**
     * Reloads the grid and keeps the given person selected. Selecting a person
     * that was not selected before (e.g. a newly created one) navigates to its
     * edit route via the selection listener.
     */
    void refreshAndSelect(SamplePerson person) {
        grid.getDataProvider().refreshItem(person);
        grid.select(person);
    }
}
