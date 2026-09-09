package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B-116 · the acceptance PDF's layout — a plain, legible internal record, not
 * a branded document, per the backlog line's own instruction not to
 * over-engineer it.
 *
 * <h2>OpenPDF, reused rather than re-decided</h2>
 *
 * <p>{@code PdfReportExporter} (A-064, {@code feature/reports/export}) is the
 * only prior user of {@code com.lowagie.text} in this codebase, and the pom's
 * own note is why this class does not reach for a second library: OpenPDF is
 * already a dependency of the {@code api} module, and PLAN.md names nothing
 * for PDF, so a second choice here would be a second answer to a question the
 * build already settled. This class does not import
 * {@code feature.reports.export} — Stream A's package — it only follows the
 * same font/colour convention by eye.
 *
 * <h2>{@link #rows} is separate from {@link #render} on purpose</h2>
 *
 * <p>OpenPDF writes bytes; nothing in this codebase reads a PDF back to
 * assert its text ({@code ReportExporterTest}'s own PDF cases check only the
 * {@code %PDF-} magic bytes and a length floor). Splitting the field mapping
 * out into {@link #rows} means the part most likely to be wrong — which
 * facts appear, in what order, with what fallback for a missing one — is
 * asserted directly, and {@link #render} only has to prove it still produces
 * a well-formed document.
 */
@Component
class ObSignoffCertificateRenderer {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm:ss 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private static final Color INK = new Color(0x11, 0x18, 0x27);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color RULE = new Color(0xE5, 0xE8, 0xF0);
    private static final Color HEADER_FILL = new Color(0xF1, 0xF3, 0xF9);

    /** What the certificate needs, gathered by the caller from the row and its readers. */
    record Data(
            long signoffId,
            ObSignoffKind kind,
            String clientName,
            String productName,
            String stepTitle,
            String signedName,
            Instant signedAt,
            String signedIp,
            String signedUserAgent,
            String otpChannel,
            String acceptanceNote) {
    }

    /**
     * Label/value pairs, in the order the certificate prints them. Package-only
     * so a unit test can assert the mapping without opening a PDF.
     */
    List<String[]> rows(Data data) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Client", orDash(data.clientName())});
        boolean goLive = data.kind() == ObSignoffKind.GO_LIVE;
        rows.add(new String[]{goLive ? "Milestone" : "Service",
                goLive ? "Go-Live" : orDash(data.stepTitle())});
        if (data.productName() != null && !data.productName().isBlank()) {
            rows.add(new String[]{"Product / journey", data.productName()});
        }
        rows.add(new String[]{"Accepted by", orDash(data.signedName())});
        rows.add(new String[]{"Accepted at",
                data.signedAt() == null ? "—" : TIMESTAMP.format(data.signedAt())});
        rows.add(new String[]{"IP address", orDash(data.signedIp())});
        rows.add(new String[]{"User agent", orDash(data.signedUserAgent())});
        rows.add(new String[]{"OTP verified via", orDash(data.otpChannel())});
        if (data.acceptanceNote() != null && !data.acceptanceNote().isBlank()) {
            rows.add(new String[]{"Note", data.acceptanceNote()});
        }
        return rows;
    }

    /** The certificate, rendered. */
    byte[] render(Data data) {
        Document document = new Document(PageSize.A4, 48, 48, 54, 54);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(heading("Acceptance Certificate"));
            document.add(subheading("Sign-off #" + data.signoffId()));
            document.add(spacer());
            document.add(table(rows(data)));
        } catch (DocumentException e) {
            // Every failure here is a programming error in the layout above —
            // fixed widths, fixed column count, bytes we generated ourselves —
            // never a fact about the caller's input. Unchecked, on
            // ObAttachmentPipeline.sha256's own reasoning for an unreachable
            // checked exception.
            throw new IllegalStateException("could not render the acceptance PDF", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private static PdfPTable table(List<String[]> rows) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1f, 2.2f});

        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
        for (String[] row : rows) {
            PdfPCell label = new PdfPCell(new Phrase(row[0], labelFont));
            label.setBackgroundColor(HEADER_FILL);
            label.setBorderColor(RULE);
            label.setPadding(6);
            table.addCell(label);

            PdfPCell value = new PdfPCell(new Phrase(row[1], valueFont));
            value.setBorderColor(RULE);
            value.setPadding(6);
            table.addCell(value);
        }
        return table;
    }

    private static Paragraph heading(String text) {
        return new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, INK));
    }

    private static Paragraph subheading(String text) {
        return new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED));
    }

    private static Paragraph spacer() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(10);
        return p;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
