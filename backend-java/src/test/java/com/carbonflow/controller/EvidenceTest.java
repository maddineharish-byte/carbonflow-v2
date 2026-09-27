package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.service.EvidenceStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 modules 8 + 10: the evidence vault — 25 MB / MIME allow-list /
 * magic-byte validation, SHA-256 hashing, streamed download with sanitized
 * headers, tenant-validated linking, append-only versioning, the governed
 * delete rule and explicit cross-tenant IDOR coverage. storagePath never
 * appears in any response.
 */
class EvidenceTest extends AuditTestBase {

    private static final String ACME_ADMIN = "admin@acmeglobal.com";
    private static final String ACME_AUDITOR = "auditor@ey-assurance.com";
    private static final String APEX_ADMIN = "admin@apexcorp.com";

    private static final byte[] VALID_PDF =
            ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n"
                    + "%%EOF\n").getBytes(StandardCharsets.US_ASCII);

    private Api upload(String token, String fileName, String contentType,
                       byte[] content) throws Exception {
        return postMultipart("/api/v1/evidence/upload", token, null, fileName,
                contentType, content);
    }

    private int evidenceListSize(String token) throws Exception {
        Api api = getJson("/api/v1/evidence", token);
        assertEquals(200, api.status(), api.body().toString());
        return api.body().path("data").size();
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    @Test
    void uploadStoresSha256AndNeverExposesStoragePath() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        int before = evidenceListSize(token);

        Api api = upload(token, "q3-invoices.pdf", "application/pdf", VALID_PDF);
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Evidence stored with SHA-256 verification.",
                api.body().path("message").asText());

        JsonNode record = api.body().path("data");
        assertEquals(EvidenceStorageService.sha256Hex(VALID_PDF),
                record.path("sha256Hash").asText(),
                "SHA-256 of the exact stored bytes");
        assertEquals(VALID_PDF.length, record.path("fileSizeBytes").asLong());
        assertEquals("application/pdf", record.path("mimeType").asText());
        assertEquals("q3-invoices.pdf", record.path("fileName").asText());
        assertEquals(SeedIds.ORG_ACME, record.path("organizationId").asText());
        assertEquals(SeedIds.USER_ACME_ADMIN, record.path("uploadedBy").asText());
        assertTrue(record.path("sha256Hash").asText().matches("[0-9a-f]{64}"));

        String raw = api.body().toString();
        assertFalse(raw.contains("storagePath"),
                "storagePath must never be serialized: " + raw);
        assertFalse(raw.contains("vault_storage"),
                "physical location must never be serialized: " + raw);

        // Version 1 history row exists from the first upload.
        String evidenceId = record.path("id").asText();
        Api detail = getJson("/api/v1/evidence/" + evidenceId, token);
        assertEquals(200, detail.status(), detail.body().toString());
        JsonNode versions = detail.body().path("data").path("versions");
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).path("versionNumber").asInt());
        assertEquals(record.path("sha256Hash").asText(),
                versions.get(0).path("sha256Hash").asText());
        assertEquals(0, detail.body().path("data").path("links").size());

        assertEquals(before + 1, evidenceListSize(token),
                "exactly the new record appears");
        assertTrue(listContains(getJson("/api/v1/evidence", token).body()
                        .path("data"), evidenceId));
    }

    @Test
    void uploadValidationChainMatchesTheNodeReference() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        int before = evidenceListSize(token);

        // Missing file part.
        Api missing = postMultipart("/api/v1/evidence/upload", token, null, null,
                null, null);
        assertEquals(400, missing.status(), missing.body().toString());
        assertEquals("FILE_MISSING", missing.errorCode());
        assertEquals("No file was uploaded in the request.",
                missing.errorMessage());

        // Empty upload.
        Api empty = upload(token, "empty.txt", "text/plain", new byte[0]);
        assertEquals(400, empty.status(), empty.body().toString());
        assertEquals("FILE_MISSING", empty.errorCode());

        // MIME allow-list.
        Api badMime = upload(token, "run.bin", "application/x-msdownload",
                new byte[] {0x4d, 0x5a});
        assertEquals(400, badMime.status(), badMime.body().toString());
        assertEquals("UPLOAD_FAILED", badMime.errorCode());
        assertEquals("MIME type 'application/x-msdownload' is not supported for "
                + "evidence documents.", badMime.errorMessage());

        // Magic-byte check: declared PDF, plain-text content.
        Api forged = upload(token, "forged.pdf", "application/pdf",
                "not really a pdf".getBytes(StandardCharsets.US_ASCII));
        assertEquals(400, forged.status(), forged.body().toString());
        assertEquals("UPLOAD_FAILED", forged.errorCode());
        assertEquals("File content does not match declared MIME type "
                + "'application/pdf'.", forged.errorMessage());

        // 25 MB ceiling.
        byte[] oversized = new byte[26 * 1024 * 1024];
        java.util.Arrays.fill(oversized, (byte) 'a');
        Api tooBig = upload(token, "huge.txt", "text/plain", oversized);
        assertEquals(400, tooBig.status(), tooBig.body().toString());
        assertEquals("UPLOAD_FAILED", tooBig.errorCode());
        assertEquals("File size " + oversized.length + " exceeds 25 MB limit.",
                tooBig.errorMessage());

        // A valid text file still passes (CSV/TXT are in the allow-list and
        // text has no magic bytes — content must simply contain no NUL).
        Api text = upload(token, "notes.txt", "text/plain",
                "meter readings,kg CO2e".getBytes(StandardCharsets.US_ASCII));
        assertEquals(201, text.status(), text.body().toString());

        assertEquals(before + 1, evidenceListSize(token),
                "every rejected upload persisted nothing; only the valid one did");
    }

    @Test
    void uploadLinkTargetFailuresAreUniformAcrossFailureModes() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        int before = evidenceListSize(token);

        JsonNode apexAudit = freshAudit(apex, "P5 EvApex");

        Map<String, String> foreign = Map.of("entityType", "AUDIT",
                "entityId", apexAudit.get("id").asText());
        Map<String, String> missing = Map.of("entityType", "AUDIT",
                "entityId", UUID.randomUUID().toString());
        Map<String, String> badType = Map.of("entityType", "GALAXY",
                "entityId", apexAudit.get("id").asText());
        Map<String, String> halfPair = Map.of("entityType", "AUDIT");

        Api a = postMultipart("/api/v1/evidence/upload", token, foreign,
                "a.pdf", "application/pdf", VALID_PDF);
        Api b = postMultipart("/api/v1/evidence/upload", token, missing,
                "b.pdf", "application/pdf", VALID_PDF);
        Api c = postMultipart("/api/v1/evidence/upload", token, badType,
                "c.pdf", "application/pdf", VALID_PDF);
        Api d = postMultipart("/api/v1/evidence/upload", token, halfPair,
                "d.pdf", "application/pdf", VALID_PDF);

        for (Api api : new Api[] {a, b, c, d}) {
            assertEquals(400, api.status(), api.body().toString());
            assertEquals("INVALID_EVIDENCE_RELATIONSHIP", api.errorCode(),
                    "all entity failures collapse into one response");
            assertEquals("Evidence entity is invalid for this organization.",
                    api.errorMessage());
        }
        assertEquals(before, evidenceListSize(token),
                "validation runs before any file or row is written");
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    @Test
    void downloadStreamsExactBytesAndReportsMissingFiles() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        Api upload = upload(token, "energy-report.pdf", "application/pdf",
                VALID_PDF);
        assertEquals(201, upload.status(), upload.body().toString());
        String evidenceId = upload.body().path("data").path("id").asText();

        MvcResult download = rawGet(
                "/api/v1/evidence/" + evidenceId + "/download", token);
        assertEquals(200, download.getResponse().getStatus());
        assertEquals("application/pdf",
                download.getResponse().getHeader("Content-Type"));
        assertEquals("attachment; filename=\"energy-report.pdf\"",
                download.getResponse().getHeader("Content-Disposition"),
                "Node's header sanitization contract");
        assertArrayEquals(VALID_PDF, download.getResponse().getContentAsByteArray(),
                "download returns the exact stored bytes");

        // Delete the physical file -> Node's 404 mapping and shared message.
        String storagePath = jdbc.queryForObject(
                "SELECT storage_path FROM evidence_records WHERE id = ?::uuid",
                String.class, evidenceId);
        Files.deleteIfExists(Paths.get(storagePath));

        MvcResult gone = rawGet(
                "/api/v1/evidence/" + evidenceId + "/download", token);
        assertEquals(404, gone.getResponse().getStatus());
        assertTrue(gone.getResponse().getContentAsString()
                        .contains("EVIDENCE_FILE_NOT_FOUND"),
                "Node's missing-file code: " + gone.getResponse().getContentAsString());
        assertTrue(gone.getResponse().getContentAsString()
                        .contains("The evidence file is unavailable."),
                "Node's shared unavailable message");
    }

    // ------------------------------------------------------------------
    // Linking
    // ------------------------------------------------------------------

    @Test
    void linkEndpointValidatesOwnershipDuplicatesAndPermission() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);

        JsonNode facility = createFacility(token, "P5 Ev Plant " + suffix(),
                "EV" + suffix());
        JsonNode audit = freshAudit(token, "P5 Ev Link");
        Api upload = upload(token, "linked.pdf", "application/pdf", VALID_PDF);
        assertEquals(201, upload.status(), upload.body().toString());
        String evidenceId = upload.body().path("data").path("id").asText();
        String linkUri = "/api/v1/evidence/" + evidenceId + "/link";

        Api good = postJson(linkUri, token,
                "{\"entityType\":\"FACILITY\",\"entityId\":\""
                        + facility.get("id").asText() + "\"}");
        assertEquals(201, good.status(), good.body().toString());
        assertEquals("Evidence linked.", good.body().path("message").asText());

        Api duplicate = postJson(linkUri, token,
                "{\"entityType\":\"FACILITY\",\"entityId\":\""
                        + facility.get("id").asText() + "\"}");
        assertEquals(409, duplicate.status(), duplicate.body().toString());
        assertEquals("DUPLICATE_EVIDENCE_LINK", duplicate.errorCode());

        // Same entity may also be linked to the audit.
        Api auditLink = postJson(linkUri, token,
                "{\"entityType\":\"AUDIT\",\"entityId\":\""
                        + audit.get("id").asText() + "\"}");
        assertEquals(201, auditLink.status(), auditLink.body().toString());

        // Cross-tenant facility -> uniform relationship error, no row.
        JsonNode foreignFacility = createFacility(apex,
                "Apex Ev Plant " + suffix(), "AF" + suffix());
        int linksBefore = linkCount(evidenceId);
        Api crossTenant = postJson(linkUri, token,
                "{\"entityType\":\"FACILITY\",\"entityId\":\""
                        + foreignFacility.get("id").asText() + "\"}");
        assertEquals(400, crossTenant.status(), crossTenant.body().toString());
        assertEquals("INVALID_EVIDENCE_RELATIONSHIP", crossTenant.errorCode());
        assertEquals("Evidence entity is invalid for this organization.",
                crossTenant.errorMessage());
        assertEquals(linksBefore, linkCount(evidenceId),
                "a rejected link must persist nothing");

        // Unknown id answers identically (anti-enumeration).
        Api unknown = postJson(linkUri, token,
                "{\"entityType\":\"FACILITY\",\"entityId\":\""
                        + UUID.randomUUID() + "\"}");
        assertEquals(400, unknown.status(), unknown.body().toString());
        assertEquals("INVALID_EVIDENCE_RELATIONSHIP", unknown.errorCode());
        assertEquals(crossTenant.errorMessage(), unknown.errorMessage());

        // ASSURANCE_PROVIDER holds evidence.read but not evidence.upload.
        Api forbidden = postJson(linkUri, auditor,
                "{\"entityType\":\"FACILITY\",\"entityId\":\""
                        + facility.get("id").asText() + "\"}");
        assertEquals(403, forbidden.status(), forbidden.body().toString());
        assertEquals("FORBIDDEN", forbidden.errorCode());
        assertEquals(linksBefore, linkCount(evidenceId));

        // Detail reflects the links.
        Api detail = getJson("/api/v1/evidence/" + evidenceId, token);
        assertEquals(200, detail.status(), detail.body().toString());
        assertEquals(2, detail.body().path("data").path("links").size());
        assertEquals(SeedIds.ORG_ACME, detail.body().path("data")
                .path("organizationId").asText());
    }

    private int linkCount(String evidenceId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_links WHERE evidence_record_id = ?::uuid",
                Integer.class, evidenceId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // Versioning
    // ------------------------------------------------------------------

    @Test
    void versionsAppendHistoryWithoutOverwritingPriorHashes() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);

        byte[] v1Bytes = ("%PDF-1.4\nversion one content\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] v2Bytes = ("%PDF-1.4\nversion two content\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
        String v1Sha = EvidenceStorageService.sha256Hex(v1Bytes);
        String v2Sha = EvidenceStorageService.sha256Hex(v2Bytes);

        Api upload = upload(token, "statement.pdf", "application/pdf", v1Bytes);
        assertEquals(201, upload.status(), upload.body().toString());
        String evidenceId = upload.body().path("data").path("id").asText();

        // ASSURANCE_PROVIDER lacks evidence.version.
        Api forbidden = postMultipart(
                "/api/v1/evidence/" + evidenceId + "/versions", auditor, null,
                "statement.pdf", "application/pdf", v2Bytes);
        assertEquals(403, forbidden.status(), forbidden.body().toString());
        assertEquals("FORBIDDEN", forbidden.errorCode());

        Api version = postMultipart(
                "/api/v1/evidence/" + evidenceId + "/versions", token, null,
                "statement-v2.pdf", "application/pdf", v2Bytes);
        assertEquals(201, version.status(), version.body().toString());
        assertEquals("Evidence version 2 created.",
                version.body().path("message").asText());
        assertEquals(2, version.body().path("data").path("versionNumber").asInt());
        assertEquals(v2Sha, version.body().path("data").path("sha256Hash").asText());

        // History: v1 row untouched, record repointed to the head.
        Api detail = getJson("/api/v1/evidence/" + evidenceId, token);
        JsonNode data = detail.body().path("data");
        assertEquals(v2Sha, data.path("sha256Hash").asText(),
                "record follows the newest version");
        JsonNode versions = data.path("versions");
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).path("versionNumber").asInt());
        assertEquals(v1Sha, versions.get(0).path("sha256Hash").asText(),
                "prior hash preserved (append-only history)");
        assertEquals(2, versions.get(1).path("versionNumber").asInt());
        assertEquals(v2Sha, versions.get(1).path("sha256Hash").asText());

        // Two physical files, distinct paths — nothing overwritten.
        String pathV1 = jdbc.queryForObject(
                "SELECT storage_path FROM evidence_versions "
                        + "WHERE evidence_record_id = ?::uuid AND version_number = 1",
                String.class, evidenceId);
        String pathV2 = jdbc.queryForObject(
                "SELECT storage_path FROM evidence_versions "
                        + "WHERE evidence_record_id = ?::uuid AND version_number = 2",
                String.class, evidenceId);
        assertFalse(pathV1.equals(pathV2), "each version keeps its own file");
        assertTrue(Files.exists(Paths.get(pathV1)), "v1 file still on disk");
        assertTrue(Files.exists(Paths.get(pathV2)), "v2 file on disk");

        // Download serves the head.
        MvcResult download = rawGet(
                "/api/v1/evidence/" + evidenceId + "/download", token);
        assertEquals(200, download.getResponse().getStatus());
        assertArrayEquals(v2Bytes,
                download.getResponse().getContentAsByteArray());
    }

    // ------------------------------------------------------------------
    // Governed delete
    // ------------------------------------------------------------------

    @Test
    void deleteFollowsTheAuditLinkageRuleAndPermission() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);

        // Standalone evidence deletes cleanly (rows + files).
        Api standalone = upload(token, "scratch.pdf", "application/pdf",
                VALID_PDF);
        assertEquals(201, standalone.status(), standalone.body().toString());
        String standaloneId = standalone.body().path("data").path("id").asText();
        String storagePath = jdbc.queryForObject(
                "SELECT storage_path FROM evidence_records WHERE id = ?::uuid",
                String.class, standaloneId);

        // ASSURANCE_PROVIDER lacks evidence.delete.
        Api forbidden = deleteJson("/api/v1/evidence/" + standaloneId, auditor);
        assertEquals(403, forbidden.status(), forbidden.body().toString());
        assertEquals("FORBIDDEN", forbidden.errorCode());

        // Cross-tenant delete cannot reach the row.
        Api crossTenant = deleteJson("/api/v1/evidence/" + standaloneId, apex);
        assertEquals(404, crossTenant.status(), crossTenant.body().toString());
        assertEquals("EVIDENCE_NOT_FOUND", crossTenant.errorCode());

        Api delete = deleteJson("/api/v1/evidence/" + standaloneId, token);
        assertEquals(200, delete.status(), delete.body().toString());
        assertEquals("Evidence deleted.", delete.body().path("message").asText());
        assertEquals(404, getJson("/api/v1/evidence/" + standaloneId, token).status(),
                "row gone after governed delete");
        assertFalse(Files.exists(Paths.get(storagePath)),
                "physical file removed with its rows");
        Integer versions = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_versions WHERE evidence_record_id = ?::uuid",
                Integer.class, standaloneId);
        assertEquals(0, versions, "version rows cascade with the record");

        // Audit-linked evidence is part of the audit record — never deleted.
        JsonNode audit = freshAudit(token, "P5 Ev Del");
        Api linked = upload(token, "audit-evidence.pdf", "application/pdf",
                VALID_PDF);
        assertEquals(201, linked.status(), linked.body().toString());
        String linkedId = linked.body().path("data").path("id").asText();
        assertEquals(201, postJson("/api/v1/evidence/" + linkedId + "/link", token,
                        "{\"entityType\":\"AUDIT\",\"entityId\":\""
                                + audit.get("id").asText() + "\"}").status());

        Api refused = deleteJson("/api/v1/evidence/" + linkedId, token);
        assertEquals(409, refused.status(), refused.body().toString());
        assertEquals("EVIDENCE_IN_USE", refused.errorCode());
        assertTrue(refused.errorMessage().contains("audit record"));
        assertEquals(200, getJson("/api/v1/evidence/" + linkedId, token).status(),
                "audit-linked evidence remains readable");
    }

    // ------------------------------------------------------------------
    // Tenant isolation
    // ------------------------------------------------------------------

    @Test
    void evidenceIsFullyTenantScopedAcrossEveryVerb() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);

        JsonNode audit = freshAudit(token, "P5 Ev Ten");
        Api upload = upload(token, "confidential.pdf", "application/pdf",
                VALID_PDF);
        assertEquals(201, upload.status(), upload.body().toString());
        String evidenceId = upload.body().path("data").path("id").asText();

        Api foreignList = getJson("/api/v1/evidence", apex);
        assertEquals(200, foreignList.status());
        assertFalse(listContains(foreignList.body().path("data"), evidenceId),
                "foreign evidence never appears in another tenant's list");

        Api foreignGet = getJson("/api/v1/evidence/" + evidenceId, apex);
        Api malformedGet = getJson("/api/v1/evidence/not-a-uuid", apex);
        assertEquals(404, foreignGet.status(), foreignGet.body().toString());
        assertEquals(404, malformedGet.status(), malformedGet.body().toString());
        assertEquals("EVIDENCE_NOT_FOUND", foreignGet.errorCode());
        assertEquals("EVIDENCE_NOT_FOUND", malformedGet.errorCode());
        assertEquals(foreignGet.errorMessage(), malformedGet.errorMessage(),
                "foreign and malformed ids are indistinguishable");

        MvcResult foreignDownload = rawGet(
                "/api/v1/evidence/" + evidenceId + "/download", apex);
        assertEquals(404, foreignDownload.getResponse().getStatus());

        Api foreignVersion = postMultipart(
                "/api/v1/evidence/" + evidenceId + "/versions", apex, null,
                "sneak.pdf", "application/pdf", VALID_PDF);
        assertEquals(404, foreignVersion.status(), foreignVersion.body().toString());
        assertEquals("EVIDENCE_NOT_FOUND", foreignVersion.errorCode());

        Api foreignLink = postJson("/api/v1/evidence/" + evidenceId + "/link", apex,
                "{\"entityType\":\"AUDIT\",\"entityId\":\""
                        + audit.get("id").asText() + "\"}");
        assertEquals(404, foreignLink.status(), foreignLink.body().toString());
        assertEquals("EVIDENCE_NOT_FOUND", foreignLink.errorCode());
        assertEquals(0, linkCount(evidenceId),
                "the foreign link attempt persisted nothing");

        // Own data still intact for the owner.
        assertEquals(200, getJson("/api/v1/evidence/" + evidenceId, token).status());
    }
}
