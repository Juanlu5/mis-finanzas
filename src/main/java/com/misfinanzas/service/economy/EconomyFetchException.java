package com.misfinanzas.service.economy;

/** A source could not be read; the message is shown to the user next to that source's data. */
public class EconomyFetchException extends RuntimeException {

    public EconomyFetchException(String message) {
        super(message);
    }
}
