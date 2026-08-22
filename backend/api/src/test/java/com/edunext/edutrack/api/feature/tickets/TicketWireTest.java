package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.tickets.Ticket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract's {@code Ticket} schema — {@code required: [ticketId,
 * title, level, status, cycleNo]} — against what this record actually puts on
 * the wire, the way {@code TicketWire}'s own class note now records finding it
 * wrong. Serializes with a real {@code ObjectMapper} rather than reading
 * {@link Ticket.Ticket} accessor names off the record, because the accessor
 * names are exactly what this bug hid behind: {@code ticketCode()} is the
 * right Java name and {@code "ticketId"} is the wire key {@code @JsonProperty}
 * now makes it serialize as, and a test that stopped at the record would not
 * have caught either of those disagreeing.
 */
class TicketWireTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void wireKeysMatchTheContractRequiredList() throws Exception {
        Ticket t = new Ticket();
        t.setId(348_706L);
        t.setTicketCode("CRM-26-13013");
        t.setProjectId(1L);
        t.setTitle("Perf corpus #38836");
        t.setLevel("CRITICAL");
        t.setOriginalLevel("HIGH");
        t.setStatus("IN_PROGRESS");
        t.setCurrentStage("SIGNOFF");
        t.setCurrentCycleNo((short) 1);

        String node = json.writeValueAsString(TicketWire.of(t));

        // The contract's own required list — every one of these must be a key
        // in the JSON this class produces, under the contract's own name.
        assertThat(node).contains("\"ticketId\":\"CRM-26-13013\"");
        assertThat(node).contains("\"cycleNo\":1");

        // The two names the ticket with no cycles at all found this record
        // still answering with, on the day this test was written.
        assertThat(node).doesNotContain("\"ticketCode\"");
        assertThat(node).doesNotContain("\"currentCycleNo\"");
    }
}
