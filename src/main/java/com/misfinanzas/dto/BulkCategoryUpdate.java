package com.misfinanzas.dto;

import java.util.List;

public record BulkCategoryUpdate(List<Long> ids, String category) {
}
