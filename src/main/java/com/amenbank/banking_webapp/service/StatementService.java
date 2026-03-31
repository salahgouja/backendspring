package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.ForbiddenException;
import com.amenbank.banking_webapp.exception.BankingException.NotFoundException;
import com.amenbank.banking_webapp.model.Account;
import com.amenbank.banking_webapp.model.Transaction;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.TransactionRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * GAP-8: Generates PDF account statements (Relevé de Compte).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(0, 51, 102));
    private static final Font SUBTITLE_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, new Color(0, 51, 102));
    private static final Font HEADER_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
    private static final Font CELL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.BLACK);
    private static final Font CELL_FONT_BOLD = new Font(Font.HELVETICA, 8, Font.BOLD, Color.BLACK);
    private static final Font LABEL_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, Color.DARK_GRAY);
    private static final Font VALUE_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 7, Font.ITALIC, Color.GRAY);

    private static final Color AMEN_BLUE = new Color(0, 51, 102);
    private static final Color LIGHT_GRAY = new Color(240, 240, 240);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Transactional(readOnly = true)
    public byte[] generateStatement(UUID accountId, String userEmail, LocalDate from, LocalDate to) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Compte introuvable"));

        if (!account.getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Ce compte ne vous appartient pas");
        }

        // Fetch transactions in date range
        LocalDateTime fromDT = from.atStartOfDay();
        LocalDateTime toDT = to.atTime(LocalTime.MAX);
        List<Transaction> transactions = transactionRepository
                .findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        accountId, fromDT, toDT,
                        org.springframework.data.domain.PageRequest.of(0, 10000))
                .getContent();

        try {
            return buildPdf(account, user, from, to, transactions);
        } catch (Exception e) {
            log.error("Failed to generate PDF statement for account {}: {}", accountId, e.getMessage());
            throw new BankingException("Erreur lors de la génération du relevé PDF: " + e.getMessage());
        }
    }

    private byte[] buildPdf(Account account, User user, LocalDate from, LocalDate to,
            List<Transaction> transactions) throws DocumentException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 50, 40);
        PdfWriter.getInstance(document, baos);
        document.open();

        // ── Header / Bank Identity ───────────────────────
        Paragraph bankName = new Paragraph("AMEN BANK", TITLE_FONT);
        bankName.setAlignment(Element.ALIGN_CENTER);
        document.add(bankName);

        Paragraph bankInfo = new Paragraph(
                "Siège Social: Avenue Mohamed V — 1002 Tunis — RC B1111201997",
                FOOTER_FONT);
        bankInfo.setAlignment(Element.ALIGN_CENTER);
        bankInfo.setSpacingAfter(10);
        document.add(bankInfo);

        // ── Separator line ───────────────────────────────
        PdfPTable separator = new PdfPTable(1);
        separator.setWidthPercentage(100);
        PdfPCell sepCell = new PdfPCell();
        sepCell.setBorderWidthBottom(2);
        sepCell.setBorderColorBottom(AMEN_BLUE);
        sepCell.setBorderWidthTop(0);
        sepCell.setBorderWidthLeft(0);
        sepCell.setBorderWidthRight(0);
        sepCell.setFixedHeight(5);
        separator.addCell(sepCell);
        document.add(separator);

        // ── Title ────────────────────────────────────────
        Paragraph title = new Paragraph("RELEVÉ DE COMPTE", SUBTITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(10);
        title.setSpacingAfter(15);
        document.add(title);

        // ── Account Info Table ───────────────────────────
        PdfPTable infoTable = new PdfPTable(4);
        infoTable.setWidthPercentage(100);
        infoTable.setWidths(new float[] { 1.2f, 2f, 1.2f, 2f });

        addInfoCell(infoTable, "Titulaire:", user.getFullNameFr());
        addInfoCell(infoTable, "CIN:", user.getCin());
        addInfoCell(infoTable, "N° Compte:", account.getAccountNumber());
        addInfoCell(infoTable, "Type:", account.getAccountType().name());
        addInfoCell(infoTable, "Devise:", account.getCurrency());
        addInfoCell(infoTable, "Agence:", user.getAgency() != null ? user.getAgency().getBranchName() : "N/A");
        addInfoCell(infoTable, "Période:", from.format(DATE_FMT) + " — " + to.format(DATE_FMT));
        addInfoCell(infoTable, "Solde actuel:", String.format("%.3f %s", account.getBalance(), account.getCurrency()));

        infoTable.setSpacingAfter(15);
        document.add(infoTable);

        // ── Transactions Table ───────────────────────────
        PdfPTable txTable = new PdfPTable(6);
        txTable.setWidthPercentage(100);
        txTable.setWidths(new float[] { 1.5f, 0.8f, 1.2f, 1.2f, 1.2f, 2.5f });

        // Header row
        addHeaderCell(txTable, "Date");
        addHeaderCell(txTable, "Type");
        addHeaderCell(txTable, "Montant");
        addHeaderCell(txTable, "Solde Après");
        addHeaderCell(txTable, "Catégorie");
        addHeaderCell(txTable, "Description");

        // Data rows
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalDebit = BigDecimal.ZERO;
        boolean alternate = false;

        for (Transaction tx : transactions) {
            Color bgColor = alternate ? LIGHT_GRAY : Color.WHITE;

            addDataCell(txTable, tx.getCreatedAt().format(DATETIME_FMT), bgColor, false);
            addDataCell(txTable, tx.getType().name(), bgColor, false);
            addDataCell(txTable, String.format("%.3f", tx.getAmount()), bgColor, true);
            addDataCell(txTable, String.format("%.3f", tx.getBalanceAfter()), bgColor, true);
            addDataCell(txTable, tx.getCategory() != null ? tx.getCategory() : "-", bgColor, false);
            addDataCell(txTable, tx.getDescription() != null ? tx.getDescription() : "-", bgColor, false);

            if (tx.getType() == Transaction.TransactionType.CREDIT) {
                totalCredit = totalCredit.add(tx.getAmount());
            } else {
                totalDebit = totalDebit.add(tx.getAmount());
            }

            alternate = !alternate;
        }

        document.add(txTable);

        // ── Summary ──────────────────────────────────────
        Paragraph summary = new Paragraph();
        summary.setSpacingBefore(15);
        summary.add(new Chunk("Résumé de la période\n", SUBTITLE_FONT));
        summary.add(new Chunk(String.format(
                "Nombre d'opérations: %d  |  Total Crédits: %.3f %s  |  Total Débits: %.3f %s  |  Solde actuel: %.3f %s",
                transactions.size(), totalCredit, account.getCurrency(),
                totalDebit, account.getCurrency(),
                account.getBalance(), account.getCurrency()), VALUE_FONT));
        document.add(summary);

        // ── Footer ───────────────────────────────────────
        Paragraph footer = new Paragraph();
        footer.setSpacingBefore(30);
        footer.add(new Chunk(
                "Document généré automatiquement le " + LocalDateTime.now().format(DATETIME_FMT) +
                        " — Ce relevé est fourni à titre informatif et ne constitue pas un document contractuel.\n" +
                        "Amen Bank — Tél: 71 148 000 — www.amenbank.com.tn",
                FOOTER_FONT));
        footer.setAlignment(Element.ALIGN_CENTER);
        document.add(footer);

        document.close();
        log.info("PDF statement generated for account {} ({} transactions)", account.getAccountNumber(),
                transactions.size());
        return baos.toByteArray();
    }

    private void addInfoCell(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, LABEL_FONT));
        labelCell.setBorder(0);
        labelCell.setPaddingBottom(4);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, VALUE_FONT));
        valueCell.setBorder(0);
        valueCell.setPaddingBottom(4);
        table.addCell(valueCell);
    }

    private void addHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_FONT));
        cell.setBackgroundColor(AMEN_BLUE);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private void addDataCell(PdfPTable table, String text, Color bgColor, boolean alignRight) {
        PdfPCell cell = new PdfPCell(new Phrase(text, CELL_FONT));
        cell.setBackgroundColor(bgColor);
        cell.setHorizontalAlignment(alignRight ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        cell.setPadding(3);
        cell.setBorderColor(Color.LIGHT_GRAY);
        table.addCell(cell);
    }
}
