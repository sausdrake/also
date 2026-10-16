package com.example.autoauction.config;

import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

@Slf4j
@Configuration
public class OpenAPIConfig {

    @Bean
    public OperationCustomizer customOperationCustomizer() {
        return (operation, handlerMethod) -> {
            try {
                // Скрываем параметры с аннотацией @CurrentUser
                if (handlerMethod.getMethodParameters() != null) {
                    for (var parameter : handlerMethod.getMethodParameters()) {
                        if (parameter.hasParameterAnnotation(CurrentUser.class)) {
                            String paramName = parameter.getParameterName();
                            if (operation.getParameters() != null && paramName != null) {
                                operation.getParameters().removeIf(param ->
                                        param.getName() != null && param.getName().equals(paramName));
                                log.debug("Removed parameter: {}", paramName);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error while customizing operation: {}", e.getMessage());
            }
            return operation;
        };
    }

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Auto Auction API")
                        .version("1.0.0")
                        .description("REST API для системы автомобильных аукционов\n\n" +
                                "### Модули:\n" +
                                "* **User** - управление пользователями\n" +
                                "* **Auction** - управление аукционами\n" +
                                "* **Vehicle** - управление транспортными средствами\n" +
                                "* **Deposit** - управление депозитами\n" +
                                "* **Admin** - административные функции")
                        .contact(new Contact()
                                .name("Temirlan Alikovich")
                                .email("your.email@example.com")
                                .url("https://example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")))
                // DEV-01: no fixed server list - springdoc then names the origin each request came from,
                // so Swagger UI's "Try it out" calls the backend it was opened on. The hard-coded
                // http://localhost:8080 sent every call to a port nothing listens on (the backend runs on 8081).
                .addSecurityItem(new SecurityRequirement()
                        .addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                                .name(securitySchemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Введите JWT токен. Пример: Bearer eyJhbGciOiJIUzI1NiJ9...")));
    }
}