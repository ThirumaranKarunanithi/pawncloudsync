package com.magizhchi.cloud.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Gives the admin page the sentence, not just the status code.
 *
 * Spring's default error body drops the reason unless
 * server.error.include-message=always, and that switch applies to every
 * endpoint — including the ones the phones and the sync agents call, where
 * an unexpected exception's message has no business being. So this is
 * scoped to the admin controller alone: its refusals are written to be read
 * by a person ("shop id must be 2-40 characters..."), and the page shows
 * them as they are.
 *
 * Anything that is NOT a deliberate refusal is logged in full here and
 * answered with a plain sentence, so an internal message never reaches the
 * browser by accident.
 */
@RestControllerAdvice(assignableTypes = AdminController.class)
public class AdminErrors {
    private static final Logger log = LoggerFactory.getLogger(AdminErrors.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> refused(ResponseStatusException e) {
        String message = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", message, "status", e.getStatusCode().value()));
    }

    /** A body the page did not build — bad JSON is the caller's fault, not a fault here. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        log.warn("admin console got an unreadable body: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("message", "That request could not be read. Fill the boxes and try again.",
                             "status", 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("admin console failed: {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Something went wrong here. The cloud log has the detail.",
                             "status", 500));
    }
}
