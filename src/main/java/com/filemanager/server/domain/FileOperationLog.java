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

    // 이 PC에서 발생한 기록만 조회할 수 있도록 PC 식별자를 저장합니다.
    // 기존 H2 데이터베이스의 기록을 보존할 수 있도록 nullable로 둡니다.
    @Column(length = 128)
    private String pcIdentifier;

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
            String pcIdentifier,
            String fileName,
            String category,
            String status,
            String sourcePath,
            String destinationPath,
            String detail) {
        this.pcIdentifier = pcIdentifier;
        this.fileName = fileName;
        this.category = category;
        this.status = status;
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPcIdentifier() {
        return pcIdentifier;
    }

    public String getFileName() {
        return fileName;
    }

    public String getCategory() {
        return category;
    }

    public String getStatus() {
        return status;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getDestinationPath() {
        return destinationPath;
    }

    public String getDetail() {
        return detail;
    }

    public long getOccurredAt() {
        return occurredAt == null ? 0L : occurredAt.toEpochMilli();
    }
}