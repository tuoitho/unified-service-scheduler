package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.ServiceBay;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceBayRepository extends JpaRepository<ServiceBay, UUID> {

    List<ServiceBay> findByDealershipIdOrderByBayNumberAscIdAsc(UUID dealershipId);
}
