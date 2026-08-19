package com.edunext.edutrack.api.feature.tickets;

/** B-028/B-029 · why this client cannot go on a new ticket. Always 400, keyed on clientId. */
class ClientNotSelectableException extends RuntimeException {

    private ClientNotSelectableException(String message) {
        super(message);
    }

    static ClientNotSelectableException noSuchClient(Long clientId) {
        return new ClientNotSelectableException("No client with id %d.".formatted(clientId));
    }

    static ClientNotSelectableException inactive(Long clientId, String status) {
        return new ClientNotSelectableException(
                "Client %d is %s and cannot be named on a new ticket. Existing tickets keep it."
                        .formatted(clientId, status));
    }

    static ClientNotSelectableException noPrimaryContact(Long clientId) {
        return new ClientNotSelectableException(
                ("Client %d has no primary contact yet, so it is not selectable on a ticket. "
                        + "Add one on the client record first.").formatted(clientId));
    }
}
