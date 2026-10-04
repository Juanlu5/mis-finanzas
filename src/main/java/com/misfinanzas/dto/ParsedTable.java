package com.misfinanzas.dto;

import java.util.List;
import java.util.Map;

/** A spreadsheet/CSV reduced to its header row and data rows, after stripping
 * any title/metadata rows a bank export places above the real table. */
public record ParsedTable(List<String> columns, List<Map<String, String>> rows) {
}
