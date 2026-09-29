package org.vaadin.demo.sizing;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import javax.sql.DataSource;

import com.vaadin.flow.theme.aura.Aura;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.autoconfigure.init.SqlInitializationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.vaadin.demo.sizing.app.data.SamplePersonRepository;

/**
 * The entry point of the Spring Boot application.
 */
@SpringBootApplication
@EnableConfigurationProperties(SqlInitializationProperties.class)
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("styles.css")
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    @Bean
    ApplicationDataSourceScriptDatabaseInitializer customInitializer(DataSource dataSource,
                                                                     SqlInitializationProperties properties,
                                                                     SamplePersonRepository repository) {
        // Only run schema.sql/data.sql when the DB is empty
        return new ApplicationDataSourceScriptDatabaseInitializer(dataSource, properties) {
            @Override
            public boolean initializeDatabase() {
                return (repository.count() == 0L) && super.initializeDatabase();
            }
        };
    }
}
