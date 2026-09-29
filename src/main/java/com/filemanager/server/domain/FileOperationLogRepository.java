package com.filemanager.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileOperationLogRepository
        extends JpaRepository<FileOperationLog, Long> {
}