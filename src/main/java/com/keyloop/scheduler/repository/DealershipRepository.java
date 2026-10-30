package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Dealership;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealershipRepository extends JpaRepository<Dealership, UUID> {

    List<Dealership> findAllByOrderByCodeAsc();
}
