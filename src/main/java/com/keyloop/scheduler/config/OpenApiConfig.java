package com.keyloop.scheduler.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI unifiedServiceSchedulerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Unified Service Scheduler API")
                        .version("1.0.0")
                        .description("Resource-constrained automotive dealership service appointment scheduling API "
                                + "with real-time bay and qualified technician allocation.")
                        .contact(new Contact()
                                .name("Platform Engineering")
                                .email("engineering@keyloop.example")));
    }
}
