package com.filemanager.server.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FileOperationLogRepository
        extends JpaRepository<FileOperationLog, Long> {

    List<FileOperationLog> findTop100ByPcIdentifierOrderByOccurredAtDesc(
            String pcIdentifier
    );

    @Modifying
    @Transactional
    @Query(
        "update FileOperationLog entry " +
        "set entry.pcIdentifier = :pcIdentifier " +
        "where entry.pcIdentifier is null"
    )
    int assignMissingPcIdentifier(
            @Param("pcIdentifier") String pcIdentifier
    );
}