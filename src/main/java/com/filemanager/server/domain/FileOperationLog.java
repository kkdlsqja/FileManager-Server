package com.filemanager.server.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "file_operation_logs")
public class FileOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 1200)
    private String sourcePath;

    @Column(length = 1200)
    private String destinationPath;

    @Column(length = 1000)
    private String detail;

    @Column(nullable = false)
    private Instant occurredAt;

    protected FileOperationLog() {
    }

    public FileOperationLog(
            String fileName,
            String category,
            String status,
            String sourcePath,
            String destinationPath,
            String detail) {
        this.fileName = fileName;
        this.category = category;
        this.status = status;
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }
}