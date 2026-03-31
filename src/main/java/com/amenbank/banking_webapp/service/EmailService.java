package com.amenbank.banking_webapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * GAP-23: Email notification service.
 * Sends emails asynchronously for critical banking events.
 * In dev mode (default), emails are logged but not sent (no SMTP configured).
 * In production, configure spring.mail.* properties in application.yml.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    /**
     * Send an email notification asynchronously.
     * In dev mode (emailEnabled=false), the email is only logged.
     */
    @Async
    public void sendEmail(String to, String subject, String body) {
        if (!emailEnabled) {
            log.info("📧 [DEV MODE — Email not sent] To: {} | Subject: {} | Body: {}",
                    to, subject, body.substring(0, Math.min(body.length(), 100)));
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email sent to {} — subject: {}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            // Don't throw — email failure should never break business logic
        }
    }

    // ── Convenience methods for banking events ───────────

    @Async
    public void sendTransferConfirmation(String to, String senderAccount, String receiverAccount,
                                          String amount, String reference) {
        String subject = "Confirmation de virement — " + reference;
        String body = String.format("""
                Cher(e) client(e),
                
                Votre virement a été effectué avec succès :
                
                  Référence : %s
                  Compte émetteur : %s
                  Compte destinataire : %s
                  Montant : %s TND
                
                Si vous n'êtes pas à l'origine de cette opération, contactez immédiatement votre agence.
                
                Cordialement,
                Amen Bank — Service Banque en ligne
                Tél : 71 148 000
                """, reference, senderAccount, receiverAccount, amount);

        sendEmail(to, subject, body);
    }

    @Async
    public void sendCreditStatusUpdate(String to, String creditType, String amount,
                                        String status, String comment) {
        String subject = "Mise à jour de votre demande de crédit";
        String body = String.format("""
                Cher(e) client(e),
                
                Le statut de votre demande de crédit a été mis à jour :
                
                  Type : %s
                  Montant : %s TND
                  Nouveau statut : %s
                  %s
                
                Connectez-vous à votre espace Amen Bank en ligne pour plus de détails.
                
                Cordialement,
                Amen Bank — Service Crédits
                """, creditType, amount, status,
                comment != null ? "Commentaire : " + comment : "");

        sendEmail(to, subject, body);
    }

    @Async
    public void sendSecurityAlert(String to, String alertType, String details) {
        String subject = "⚠️ Alerte de sécurité — Amen Bank";
        String body = String.format("""
                Cher(e) client(e),
                
                Notre système a détecté une activité inhabituelle sur votre compte :
                
                  Type d'alerte : %s
                  Détails : %s
                
                Si vous n'êtes pas à l'origine de cette activité, veuillez :
                1. Changer immédiatement votre mot de passe
                2. Contacter votre agence Amen Bank
                3. Appeler le service client : 71 148 000
                
                Cordialement,
                Amen Bank — Service Sécurité
                """, alertType, details);

        sendEmail(to, subject, body);
    }

    @Async
    public void sendPasswordResetOtp(String to, String otpCode) {
        String subject = "Code de réinitialisation — Amen Bank";
        String body = String.format("""
                Cher(e) client(e),
                
                Vous avez demandé la réinitialisation de votre mot de passe.
                
                Votre code de vérification : %s
                
                Ce code est valable 15 minutes. Ne le partagez jamais avec personne.
                
                Si vous n'avez pas fait cette demande, ignorez ce message.
                
                Cordialement,
                Amen Bank — Service Sécurité
                """, otpCode);

        sendEmail(to, subject, body);
    }
}

