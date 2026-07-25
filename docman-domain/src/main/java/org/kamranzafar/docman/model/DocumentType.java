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
    STATEMENTS("statements",
            "Periodic account statements, such as bank, credit card, or brokerage statements, "
                    + "summarizing transactions and balances over a period"),
    INVOICES("invoices",
            "Bills or requests for payment for goods or services rendered, itemizing charges, "
                    + "quantities, and the amount due"),
    POLICY_DOCUMENTS("policyDocuments",
            "Internal or organizational policy documents describing rules, procedures, guidelines, "
                    + "or terms of service - not insurance policies"),
    COMPLIANCE_CERTIFICATES("complianceCertificates",
            "Certificates or attestations confirming compliance with a regulation, standard, or "
                    + "audit, such as an ISO certification or a regulatory attestation"),
    INSURANCE_DOCUMENTS("insuranceDocuments",
            "Insurance policies, coverage summaries, declarations pages, or claims documentation "
                    + "issued by an insurer"),
    CONTRACTS("contracts",
            "Legally binding agreements between two or more parties establishing their obligations, "
                    + "terms, and conditions"),
    UNKNOWN("unknown", "The document does not clearly match any of the other categories");

    private final String label;
    private final String description;

    DocumentType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
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
                .filter(type -> {
                    String label = type.label.toLowerCase();
                    return normalized.equals(label) || normalized.contains(label);
                })
                .findFirst()
                .orElse(UNKNOWN);
    }
}
