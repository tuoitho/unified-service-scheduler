package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.ServiceType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTypeRepository extends JpaRepository<ServiceType, UUID> {

    List<ServiceType> findAllByOrderByCodeAsc();
}
