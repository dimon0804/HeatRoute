package ru.lct.heatroute.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CalculationJobRepository extends JpaRepository<CalculationJobEntity, UUID> {

    Page<CalculationJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<CalculationJobEntity> findByDatasetIdOrderByCreatedAtDesc(UUID datasetId);

    List<CalculationJobEntity> findByStatusIn(List<JobStatus> statuses);
}
