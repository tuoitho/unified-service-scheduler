package com.keyloop.scheduler.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.api.dto.DealershipSummaryResponse;
import com.keyloop.scheduler.api.dto.ServiceTypeResponse;
import com.keyloop.scheduler.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class SeedDataSmokeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void repeatableDemoSeedMigrationLoadsAndCatalogEndpointsReturnSeededDealershipsAndServices() {
        ResponseEntity<DealershipSummaryResponse[]> dealerships = restTemplate.getForEntity(
                "/api/v1/dealerships", DealershipSummaryResponse[].class);
        assertThat(dealerships.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dealerships.getBody()).isNotNull();
        assertThat(dealerships.getBody().length).isGreaterThanOrEqualTo(2);

        ResponseEntity<ServiceTypeResponse[]> serviceTypes = restTemplate.getForEntity(
                "/api/v1/service-types", ServiceTypeResponse[].class);
        assertThat(serviceTypes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(serviceTypes.getBody()).isNotNull();
        assertThat(serviceTypes.getBody().length).isGreaterThanOrEqualTo(4);
    }
}
