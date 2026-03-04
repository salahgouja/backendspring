package com.amenbank.banking_webapp.controller;

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
@SecurityRequirement(name = "Bearer Authentication")
public class NotificationController {

        private static final String USER_NOT_FOUND = "Utilisateur introuvable";

        private final NotificationRepository notificationRepository;
        private final UserRepository userRepository;

        @GetMapping
        @Operation(summary = "Lister mes notifications", description = "Retourne toutes les notifications triées par date décroissante.")
        public ResponseEntity<List<Notification>> getMyNotifications(
                        @AuthenticationPrincipal UserDetails userDetails) {
                User user = findUser(userDetails);
                return ResponseEntity.ok(notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId()));
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
        @Operation(summary = "Marquer comme lue", description = "Marque une notification spécifique comme lue.")
        public ResponseEntity<Notification> markAsRead(
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
                return ResponseEntity.ok(notification);
        }

        // ── Helper ─────────────────────────────────────────────
        private User findUser(UserDetails userDetails) {
                return userRepository.findByEmail(userDetails.getUsername())
                                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        }
}
