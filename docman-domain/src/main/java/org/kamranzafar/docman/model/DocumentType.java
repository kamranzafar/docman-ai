/*
 *  Copyright 2026 Kamran Zafar
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.kamranzafar.docman.model;

import java.util.Arrays;

/**
 * The fixed set of categories the AI classifier can assign to a document.
 */
public enum DocumentType {
    STATEMENTS("statements"),
    INVOICES("invoices"),
    POLICY_DOCUMENTS("policy documents"),
    COMPLIANCE_CERTIFICATES("compliance certificates"),
    INSURANCE_DOCUMENTS("insurance documents"),
    CONTRACTS("contracts"),
    UNKNOWN("unknown");

    private final String label;

    DocumentType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Matches a free-form model response against the known labels, falling back to
     * UNKNOWN for anything that doesn't clearly match - LLM output isn't guaranteed
     * to be an exact label even when prompted to only respond with one.
     */
    public static DocumentType fromLabel(String value) {
        if (value == null) {
            return UNKNOWN;
        }

        String normalized = value.trim().toLowerCase().replaceAll("[^a-z ]", "");

        return Arrays.stream(values())
                .filter(type -> type != UNKNOWN)
                .filter(type -> normalized.equals(type.label) || normalized.contains(type.label))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
