package ru.lct.heatroute.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DatasetRepository extends JpaRepository<DatasetEntity, UUID> {

    Page<DatasetEntity> findAllByOrderByUploadedAtDesc(Pageable pageable);

    /** Наборы старше срока хранения — их файлы подлежат удалению из временного хранилища. */
    List<DatasetEntity> findByUploadedAtBefore(OffsetDateTime threshold);
}
