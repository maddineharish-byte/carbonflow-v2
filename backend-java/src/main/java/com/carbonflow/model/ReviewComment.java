package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code review_comments} row (V1) — audit discussion thread and the
 * server-written transition log ("Transitioned status from [X] to [Y] …").
 *
 * <p>{@code userName}/{@code userRole} are hydrated at read time from the
 * author's active membership (the Node dev path denormalized them at write
 * time; PostgreSQL stores only {@code user_id} — additive read fields).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewComment {

    private String id;
    private String auditId;
    private String userId;
    private String userName;
    private String userRole;
    private String commentText;
    private Instant createdAt;

    public ReviewComment() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    public String getCommentText() {
        return commentText;
    }

    public void setCommentText(String commentText) {
        this.commentText = commentText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
