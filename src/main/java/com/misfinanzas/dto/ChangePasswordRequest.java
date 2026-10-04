package com.misfinanzas.dto;

public record ChangePasswordRequest(String current_password, String new_password) {
}
