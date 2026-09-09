package com.edunext.edutrack.api.feature.onboarding.signoff;

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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B-118 · the support handover note's layout — {@code
 * ObSignoffCertificateRenderer}'s own shape and the same instruction not to
 * over-engineer it: a plain, legible internal record for whoever picks up
 * support after go-live, not a branded document.
 *
 * <p>OpenPDF, on the same class's own reasoning for reusing rather than
 * re-deciding — it is already the {@code api} module's one PDF dependency.
 */
@Component
class ObGoLiveHandoverRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm:ss 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private static final Color INK = new Color(0x11, 0x18, 0x27);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color RULE = new Color(0xE5, 0xE8, 0xF0);
    private static final Color HEADER_FILL = new Color(0xF1, 0xF3, 0xF9);

    /** Label/value pairs, in print order. Package-only so a unit test can assert the mapping directly. */
    List<String[]> rows(ObGoLiveHandoverReader.Data data) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Client", orDash(data.clientName())});
        rows.add(new String[]{"Onboarding started",
                data.onboardingDate() == null ? "—" : DATE.format(data.onboardingDate())});
        rows.add(new String[]{"Live from",
                data.liveAt() == null ? "—" : TIMESTAMP.format(data.liveAt())});
        rows.add(new String[]{"Products / journeys",
                data.productNames() == null || data.productNames().isEmpty()
                        ? "—" : String.join(", ", data.productNames())});
        List<ObGoLiveHandoverReader.Contact> contacts = data.contacts() == null ? List.of() : data.contacts();
        if (contacts.isEmpty()) {
            rows.add(new String[]{"Contacts", "—"});
        }
        for (ObGoLiveHandoverReader.Contact contact : contacts) {
            rows.add(new String[]{
                    contact.primary() ? "Primary contact" : "Contact",
                    contactLine(contact)});
        }
        return rows;
    }

    byte[] render(ObGoLiveHandoverReader.Data data) {
        Document document = new Document(PageSize.A4, 48, 48, 54, 54);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(heading("Support Handover Note"));
            document.add(subheading(orDash(data.clientName()) + " — go-live"));
            document.add(spacer());
            document.add(table(rows(data)));
        } catch (DocumentException e) {
            // Every failure here is a programming error in the fixed layout
            // above, on bytes we generated ourselves — never a fact about the
            // client. Unchecked, ObSignoffCertificateRenderer's own reasoning.
            throw new IllegalStateException("could not render the go-live handover note", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private static String contactLine(ObGoLiveHandoverReader.Contact contact) {
        StringBuilder line = new StringBuilder(orDash(contact.name()));
        if (contact.designation() != null && !contact.designation().isBlank()) {
            line.append(" — ").append(contact.designation());
        }
        if (contact.email() != null && !contact.email().isBlank()) {
            line.append(", ").append(contact.email());
        }
        if (contact.phone() != null && !contact.phone().isBlank()) {
            line.append(", ").append(contact.phone());
        }
        return line.toString();
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
