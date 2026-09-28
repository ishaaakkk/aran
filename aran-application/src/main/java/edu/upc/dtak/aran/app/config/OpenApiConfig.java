package edu.upc.dtak.aran.app.config;

import io.swagger.v3.oas.models.media.Schema;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import java.lang.reflect.RecordComponent;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenApiCustomizer requireNonNullableRecordFields() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return;

            java.util.List<Class<?>> wrappers = java.util.List.of(
                edu.upc.dtak.aran.app.web.dto.AdminDtos.class,
                edu.upc.dtak.aran.app.web.dto.DashboardDtos.class,
                edu.upc.dtak.aran.app.web.dto.DataRepositoryDtos.class,
                edu.upc.dtak.aran.app.web.dto.DatasetDtos.class,
                edu.upc.dtak.aran.app.web.dto.GraphDtos.class,
                edu.upc.dtak.aran.app.web.dto.JobDtos.class,
                edu.upc.dtak.aran.app.web.dto.ModelDtos.class,
                edu.upc.dtak.aran.app.web.dto.ProductDtos.class,
                edu.upc.dtak.aran.app.web.dto.ProjectDtos.class,
                edu.upc.dtak.aran.app.web.dto.SchemaVersionDtos.class,
                edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubmission.class
            );

            java.util.Map<String, Class<?>> schemaToClass = new java.util.HashMap<>();
            for (Class<?> w : wrappers) {
                if (w.isRecord()) schemaToClass.put(w.getSimpleName(), w);
                for (Class<?> inner : w.getDeclaredClasses()) {
                    if (inner.isRecord()) schemaToClass.put(inner.getSimpleName(), inner);
                }
            }

            openApi.getComponents().getSchemas().forEach((schemaName, schema) -> {
                Class<?> clazz = schemaToClass.get(schemaName);
                if (clazz != null) {
                    for (RecordComponent rc : clazz.getRecordComponents()) {
                        if (!rc.isAnnotationPresent(Nullable.class)) {
                            schema.addRequiredItem(rc.getName());
                        }
                    }
                }
            });
        };
    }

    @Bean
    public OpenApiCustomizer defaultProducesJson() {
        return openApi -> {
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(pathItem -> {
                    pathItem.readOperations().forEach(operation -> {
                        if (operation.getResponses() != null) {
                            operation.getResponses().values().forEach(response -> {
                                if (response.getContent() != null && response.getContent().containsKey("*/*")) {
                                    var content = response.getContent().remove("*/*");
                                    response.getContent().put(MediaType.APPLICATION_JSON_VALUE, content);
                                }
                            });
                        }
                    });
                });
            }
        };
    }
}
