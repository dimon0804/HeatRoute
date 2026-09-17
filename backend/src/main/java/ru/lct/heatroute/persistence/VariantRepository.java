package ru.lct.heatroute.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VariantRepository extends JpaRepository<VariantEntity, UUID> {

    List<VariantEntity> findByJobIdOrderByRankAsc(UUID jobId);
}
