package com.misfinanzas.service.prices;

/** A product page could not be read; the message is for the user, in Spanish. */
public class PriceReadException extends RuntimeException {

    public PriceReadException(String message) {
        super(message);
    }
}
