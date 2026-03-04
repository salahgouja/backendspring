package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.response.NotificationResponse;
import com.amenbank.banking_webapp.exception.BankingException.ForbiddenException;
import com.amenbank.banking_webapp.exception.BankingException.NotFoundException;
import com.amenbank.banking_webapp.model.Notification;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.NotificationRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Tag(name = "Notifications", description = "Gestion des notifications utilisateur")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

        private static final String USER_NOT_FOUND = "Utilisateur introuvable";

        private final NotificationRepository notificationRepository;
        private final UserRepository userRepository;

        // ── Paginated notifications (fix #25 — DTO, fix #26 — pagination)
        @GetMapping
        @Operation(summary = "Lister mes notifications (paginé)")
        public ResponseEntity<Page<NotificationResponse>> getMyNotifications(
                        @AuthenticationPrincipal UserDetails userDetails,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {
                User user = findUser(userDetails);
                Page<NotificationResponse> notifications = notificationRepository
                        .findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(page, size))
                        .map(this::toResponse);
                return ResponseEntity.ok(notifications);
        }

        @GetMapping("/unread-count")
        @Operation(summary = "Nombre de notifications non lues")
        public ResponseEntity<Map<String, Long>> getUnreadCount(
                        @AuthenticationPrincipal UserDetails userDetails) {
                User user = findUser(userDetails);
                long count = notificationRepository.countByUserIdAndIsReadFalse(user.getId());
                return ResponseEntity.ok(Map.of("unreadCount", count));
        }

        @PutMapping("/{id}/read")
        @Transactional
        @Operation(summary = "Marquer une notification comme lue")
        public ResponseEntity<NotificationResponse> markAsRead(
                        @AuthenticationPrincipal UserDetails userDetails,
                        @PathVariable UUID id) {
                User user = findUser(userDetails);
                Notification notification = notificationRepository.findById(id)
                                .orElseThrow(() -> new NotFoundException("Notification introuvable"));
                if (!notification.getUser().getId().equals(user.getId())) {
                        throw new ForbiddenException("Cette notification ne vous appartient pas");
                }
                notification.setIsRead(true);
                notificationRepository.save(notification);
                return ResponseEntity.ok(toResponse(notification));
        }

        // ── Mark all as read (fix #16) ──────────────────────
        @PutMapping("/read-all")
        @Transactional
        @Operation(summary = "Marquer toutes les notifications comme lues")
        public ResponseEntity<Map<String, Object>> markAllAsRead(
                        @AuthenticationPrincipal UserDetails userDetails) {
                User user = findUser(userDetails);
                int updated = notificationRepository.markAllAsReadByUserId(user.getId());
                return ResponseEntity.ok(Map.of("message", "Notifications marquées comme lues", "count", updated));
        }

        // ── Delete notifications (fix #17) ──────────────────
        @DeleteMapping
        @Transactional
        @Operation(summary = "Supprimer des notifications par IDs")
        public ResponseEntity<Map<String, Object>> deleteNotifications(
                        @AuthenticationPrincipal UserDetails userDetails,
                        @RequestBody Map<String, List<UUID>> body) {
                User user = findUser(userDetails);
                List<UUID> ids = body.get("ids");
                if (ids == null || ids.isEmpty()) {
                        return ResponseEntity.ok(Map.of("message", "Aucun ID fourni", "count", 0));
                }
                int deleted = notificationRepository.deleteByUserIdAndIdIn(user.getId(), ids);
                return ResponseEntity.ok(Map.of("message", "Notifications supprimées", "count", deleted));
        }

        // ── Helpers ─────────────────────────────────────────
        private User findUser(UserDetails userDetails) {
                return userRepository.findByEmail(userDetails.getUsername())
                                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        }

        /** Fix #25: Return DTO instead of raw entity */
        private NotificationResponse toResponse(Notification n) {
                return NotificationResponse.builder()
                        .id(n.getId())
                        .type(n.getType().name())
                        .title(n.getTitle())
                        .body(n.getBody())
                        .isRead(n.getIsRead())
                        .createdAt(n.getCreatedAt())
                        .build();
        }
}
