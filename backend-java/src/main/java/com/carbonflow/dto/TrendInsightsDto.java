package com.carbonflow.dto;

import java.math.BigDecimal;
import java.util.List;

public class TrendInsightsDto {
    private Summary summary;
    private List<Anomaly> anomalies;
    private List<ReductionOpportunity> reductionOpportunities;
    private String generatedAt;
    private String modelUsed;

    public TrendInsightsDto() {}

    public TrendInsightsDto(Summary summary, List<Anomaly> anomalies, List<ReductionOpportunity> reductionOpportunities, String generatedAt, String modelUsed) {
        this.summary = summary;
        this.anomalies = anomalies;
        this.reductionOpportunities = reductionOpportunities;
        this.generatedAt = generatedAt;
        this.modelUsed = modelUsed;
    }

    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public List<Anomaly> getAnomalies() { return anomalies; }
    public void setAnomalies(List<Anomaly> anomalies) { this.anomalies = anomalies; }

    public List<ReductionOpportunity> getReductionOpportunities() { return reductionOpportunities; }
    public void setReductionOpportunities(List<ReductionOpportunity> reductionOpportunities) { this.reductionOpportunities = reductionOpportunities; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public static class Summary {
        private String headline;
        private String overallTrajectory;
        private BigDecimal confidenceScore;
        private String periodRange;
        private List<String> keyObservations;

        public Summary() {}

        public Summary(String headline, String overallTrajectory, BigDecimal confidenceScore, String periodRange, List<String> keyObservations) {
            this.headline = headline;
            this.overallTrajectory = overallTrajectory;
            this.confidenceScore = confidenceScore;
            this.periodRange = periodRange;
            this.keyObservations = keyObservations;
        }

        public String getHeadline() { return headline; }
        public void setHeadline(String headline) { this.headline = headline; }

        public String getOverallTrajectory() { return overallTrajectory; }
        public void setOverallTrajectory(String overallTrajectory) { this.overallTrajectory = overallTrajectory; }

        public BigDecimal getConfidenceScore() { return confidenceScore; }
        public void setConfidenceScore(BigDecimal confidenceScore) { this.confidenceScore = confidenceScore; }

        public String getPeriodRange() { return periodRange; }
        public void setPeriodRange(String periodRange) { this.periodRange = periodRange; }

        public List<String> getKeyObservations() { return keyObservations; }
        public void setKeyObservations(List<String> keyObservations) { this.keyObservations = keyObservations; }
    }

    public static class Anomaly {
        private String id;
        private String type;
        private String severity;
        private String title;
        private String description;
        private String affectedPeriod;
        private String scope;
        private String metricImpact;

        public Anomaly() {}

        public Anomaly(String id, String type, String severity, String title, String description, String affectedPeriod, String scope, String metricImpact) {
            this.id = id;
            this.type = type;
            this.severity = severity;
            this.title = title;
            this.description = description;
            this.affectedPeriod = affectedPeriod;
            this.scope = scope;
            this.metricImpact = metricImpact;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getAffectedPeriod() { return affectedPeriod; }
        public void setAffectedPeriod(String affectedPeriod) { this.affectedPeriod = affectedPeriod; }

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        public String getMetricImpact() { return metricImpact; }
        public void setMetricImpact(String metricImpact) { this.metricImpact = metricImpact; }
    }

    public static class ReductionOpportunity {
        private String id;
        private String category;
        private String priority;
        private String title;
        private String description;
        private BigDecimal estimatedReductionTonnes;
        private String paybackPeriod;
        private String feasibility;
        private String ghgProtocolGuidance;

        public ReductionOpportunity() {}

        public ReductionOpportunity(String id, String category, String priority, String title, String description, BigDecimal estimatedReductionTonnes, String paybackPeriod, String feasibility, String ghgProtocolGuidance) {
            this.id = id;
            this.category = category;
            this.priority = priority;
            this.title = title;
            this.description = description;
            this.estimatedReductionTonnes = estimatedReductionTonnes;
            this.paybackPeriod = paybackPeriod;
            this.feasibility = feasibility;
            this.ghgProtocolGuidance = ghgProtocolGuidance;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public BigDecimal getEstimatedReductionTonnes() { return estimatedReductionTonnes; }
        public void setEstimatedReductionTonnes(BigDecimal estimatedReductionTonnes) { this.estimatedReductionTonnes = estimatedReductionTonnes; }

        public String getPaybackPeriod() { return paybackPeriod; }
        public void setPaybackPeriod(String paybackPeriod) { this.paybackPeriod = paybackPeriod; }

        public String getFeasibility() { return feasibility; }
        public void setFeasibility(String feasibility) { this.feasibility = feasibility; }

        public String getGhgProtocolGuidance() { return ghgProtocolGuidance; }
        public void setGhgProtocolGuidance(String ghgProtocolGuidance) { this.ghgProtocolGuidance = ghgProtocolGuidance; }
    }
}
