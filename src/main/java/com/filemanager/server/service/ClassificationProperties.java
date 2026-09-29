package com.filemanager.server.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** application.yaml에서 파일명 분류 키워드를 관리합니다. */
@Component
@ConfigurationProperties(prefix = "filemanager.classification")
public class ClassificationProperties {

    private Map<String, List<String>> keywords = new LinkedHashMap<>();

    public Map<String, List<String>> getKeywords() {
        return keywords;
    }

    public void setKeywords(Map<String, List<String>> keywords) {
        this.keywords = keywords == null ? new LinkedHashMap<>() : keywords;
    }

    public List<String> getKeywordsFor(String categoryKey) {
        return keywords.getOrDefault(categoryKey, new ArrayList<>());
    }
}
