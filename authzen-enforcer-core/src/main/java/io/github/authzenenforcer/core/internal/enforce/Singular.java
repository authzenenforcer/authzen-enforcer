package io.github.authzenenforcer.core.internal.enforce;

import java.util.Locale;

/**
 * Turns an English plural into its singular with a few conservative rules, which leave words ending
 * in {@code ss}, {@code us} and {@code is} alone. Irregular words come out wrong, such as
 * {@code movies} → {@code movy}; a dictionary handles them.
 */
final class Singular {

    private Singular() {
    }

    static String of(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is")) {
            return word;
        }
        if (lower.endsWith("ies") && word.length() > 3) {
            return word.substring(0, word.length() - 3) + (Character.isUpperCase(word.charAt(word.length() - 1)) ? "Y" : "y");
        }
        if (lower.endsWith("sses") || lower.endsWith("xes") || lower.endsWith("zzes") || lower.endsWith("ches")
                || lower.endsWith("shes")) {
            return word.substring(0, word.length() - 2);
        }
        if (lower.endsWith("s") && word.length() > 1) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
