package com.filemanager.server.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "pc_devices")
public class PcDevice {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String pcName;       // PC 이름 (예: 내 방 컴퓨터)
    
    @Column(nullable = false, unique = true)
    private String pcIdentifier; // PC 고유 식별 번호 (시리얼 넘버나 고유 ID)
    
    @Column(nullable = false)
    private Long userId;         // 이 PC를 소유한 사용자 ID

    // Getter & Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPcName() { return pcName; }
    public void setPcName(String pcName) { this.pcName = pcName; }

    public String getPcIdentifier() { return pcIdentifier; }
    public void setPcIdentifier(String pcIdentifier) { this.pcIdentifier = pcIdentifier; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}