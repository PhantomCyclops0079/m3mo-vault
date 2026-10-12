package com.m3mo.vault.memory.normalize

import java.text.Normalizer
import java.util.Locale

/**
 * DefaultNormalizer - Text normalization for memory content
 *
 * Normalizes text for consistent processing:
 * - Unicode normalization (NFD → NFC)
 * - Case folding (lowercase)
 * - Whitespace normalization
 * - Punctuation handling
 * - Stop word removal (optional)
 * - Stemming support (optional)
 *
 * This preprocessing ensures:
 * - Consistent TF-IDF vectors
 * - Better semantic matching
 * - Reduced storage for normalized content
 */
class DefaultNormalizer(
    private val config: NormalizerConfig = NormalizerConfig.default()
) {
    /**
     * Normalize content for storage and processing
     * 
     * Optimized: Single-pass StringBuilder to reduce GC pressure
     *
     * @param content Raw content
     * @return Normalized content
     */
    fun normalize(content: String): String {
        if (content.isBlank()) return ""

        val result = StringBuilder(content.length)
        var lastWasWhitespace = false
        var prevChar = ' '

        for (char in content) {
            val normalized = when {
                config.lowercase && char.isUpperCase() -> char.lowercaseChar()
                else -> char
            }

            val isWhitespace = normalized.isWhitespace()
            val isPunctuation = config.removePunctuation && !normalized.isLetterOrDigit() && !isWhitespace

            when {
                isPunctuation -> {
                    if (config.normalizeWhitespace && !lastWasWhitespace) {
                        result.append(' ')
                        lastWasWhitespace = true
                    }
                }
                isWhitespace -> {
                    if (config.normalizeWhitespace) {
                        if (!lastWasWhitespace && result.isNotEmpty()) {
                            result.append(' ')
                            lastWasWhitespace = true
                        }
                    } else {
                        result.append(normalized)
                    }
                }
                else -> {
                    result.append(normalized)
                    lastWasWhitespace = false
                }
            }
            prevChar = normalized
        }

        var finalResult = result.toString().trim()

        if (config.removeStopWords) {
            finalResult = removeStopWords(finalResult)
        }

        return finalResult
    }

    /**
     * Tokenize content into words
     *
     * @param content Normalized content
     * @return List of tokens
     */
    fun tokenize(content: String): List<String> {
        return content
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() && it.length >= config.minTokenLength }
    }

    /**
     * Normalize and tokenize in one step
     */
    fun normalizeAndTokenize(content: String): List<String> {
        return tokenize(normalize(content))
    }

    /**
     * Extract keywords from content
     * Returns most significant tokens (non-stop-words, proper length)
     */
    fun extractKeywords(content: String, maxKeywords: Int = 10): List<String> {
        val tokens = normalizeAndTokenize(content)
        val wordFreq = mutableMapOf<String, Int>()

        for (token in tokens) {
            if (token !in STOP_WORDS && token.length >= 3) {
                wordFreq[token] = wordFreq.getOrDefault(token, 0) + 1
            }
        }

        return wordFreq.entries
            .sortedByDescending { it.value }
            .take(maxKeywords)
            .map { it.key }
    }

    /**
     * Apply simple stemming (suffix stripping)
     * Note: For production, consider using a proper stemmer like Porter or Snowball
     */
    fun stem(word: String): String {
        if (word.length < 4) return word

        var result = word

        val suffixes = listOf(
            "ing", "ed", "es", "s", "tion", "ness", "ment", "able", "ible", "ly"
        )

        for (suffix in suffixes) {
            if (result.endsWith(suffix) && result.length - suffix.length >= 3) {
                result = result.dropLast(suffix.length)
                break
            }
        }

        return result
    }

    /**
     * Tokenize and stem
     */
    fun tokenizeAndStem(content: String): List<String> {
        return normalizeAndTokenize(content).map { stem(it) }
    }

    /**
     * Tokenize with synonym expansion
     * 
     * Enhances TF-IDF semantic matching by adding related terms.
     * Example: "car repair" → ["car", "vehicle", "auto", "repair"]
     */
    fun tokenizeWithSynonyms(content: String): List<String> {
        val tokens = normalizeAndTokenize(content)
        val expanded = mutableListOf<String>()

        for (token in tokens) {
            expanded.add(token)
            SYNONYMS[token]?.let { synonyms ->
                expanded.addAll(synonyms)
            }
        }

        return expanded.distinct()
    }


    private fun unicodeNormalize(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return Normalizer.normalize(nfd, Normalizer.Form.NFC)
    }

    private fun normalizeWhitespace(text: String): String {
        return text
            .replace(MULTI_WHITESPACE_REGEX, " ")
            .replace("\t", " ")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }

    private fun removePunctuation(text: String): String {
        return text.replace(PUNCTUATION_REGEX, " ")
            .replace(MULTI_WHITESPACE_REGEX, " ")
    }

    private fun removeStopWords(text: String): String {
        val tokens = text.split(WHITESPACE_REGEX)
        return tokens
            .filter { it !in STOP_WORDS }
            .joinToString(" ")
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val MULTI_WHITESPACE_REGEX = Regex("\\s{2,}")
        private val PUNCTUATION_REGEX = Regex("[^\\w\\s]")

        /**
         * Lightweight synonym mapping for common terms
         * 
         * This enhances TF-IDF semantic matching by expanding
         * queries to include related terms. Only high-frequency
         * word groups are included to minimize memory footprint.
         * 
         * Patent Note: This is an enhancement to "custom stemming"
         * mentioned in the patent for improved semantic matching.
         */
        val SYNONYMS: Map<String, List<String>> = mapOf(
            "car" to listOf("vehicle", "auto", "automobile"),
            "vehicle" to listOf("car", "auto", "automobile"),
            "auto" to listOf("car", "vehicle"),
            
            "meeting" to listOf("appointment", "call", "conference"),
            "appointment" to listOf("meeting", "schedule"),
            
            "buy" to listOf("purchase", "acquire", "get"),
            "purchase" to listOf("buy", "acquire"),
            "send" to listOf("email", "message", "transmit"),
            "email" to listOf("send", "message", "mail"),
            
            "task" to listOf("todo", "job", "work"),
            "todo" to listOf("task", "item", "action"),
            "project" to listOf("work", "assignment"),
            
            "doctor" to listOf("physician", "md", "doc"),
            "lawyer" to listOf("attorney", "counsel"),
            
            "important" to listOf("urgent", "critical", "priority"),
            "urgent" to listOf("important", "critical", "asap"),
            "help" to listOf("assist", "support", "aid"),
            "problem" to listOf("issue", "bug", "error"),
            "issue" to listOf("problem", "bug", "error")
        )

        /**
         * Common English stop words
         */
        val STOP_WORDS = setOf(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "up", "about", "into", "through", "during",
            "before", "after", "above", "below", "between", "under", "again",
            "further", "then", "once", "here", "there", "when", "where", "why",
            "how", "all", "each", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very",
            "can", "will", "just", "should", "now", "i", "me", "my", "myself",
            "we", "our", "ours", "ourselves", "you", "your", "yours", "yourself",
            "yourselves", "he", "him", "his", "himself", "she", "her", "hers",
            "herself", "it", "its", "itself", "they", "them", "their", "theirs",
            "themselves", "what", "which", "who", "whom", "this", "that", "these",
            "those", "am", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "having", "do", "does", "did", "doing", "would",
            "could", "ought", "im", "youre", "hes", "shes", "its", "were", "theyre",
            "ive", "youve", "weve", "theyve", "id", "youd", "hed", "shed", "wed",
            "theyd", "ill", "youll", "hell", "shell", "well", "theyll", "isnt",
            "arent", "wasnt", "werent", "hasnt", "havent", "hadnt", "doesnt",
            "dont", "didnt", "wont", "wouldnt", "shant", "shouldnt", "cant",
            "cannot", "couldnt", "mustnt", "lets", "thats", "whos", "whats",
            "heres", "theres", "whens", "wheres", "whys", "hows"
        )

        /**
         * Singleton instance with default config
         */
        val instance: DefaultNormalizer by lazy { DefaultNormalizer() }
    }
}

/**
 * Configuration for text normalization
 */
data class NormalizerConfig(
    /**
     * Apply Unicode normalization (NFD → NFC)
     */
    val unicodeNormalize: Boolean = true,

    /**
     * Convert to lowercase
     */
    val lowercase: Boolean = true,

    /**
     * Normalize whitespace (multiple spaces → single space)
     */
    val normalizeWhitespace: Boolean = true,

    /**
     * Remove punctuation
     */
    val removePunctuation: Boolean = false,

    /**
     * Remove common stop words
     */
    val removeStopWords: Boolean = false,

    /**
     * Minimum token length to keep
     */
    val minTokenLength: Int = 2
) {
    companion object {
        /**
         * Default config - minimal normalization
         */
        fun default(): NormalizerConfig = NormalizerConfig()

        /**
         * Config for search indexing
         */
        fun forIndexing(): NormalizerConfig = NormalizerConfig(
            removePunctuation = true,
            removeStopWords = true,
            minTokenLength = 2
        )

        /**
         * Config for display (minimal changes)
         */
        fun forDisplay(): NormalizerConfig = NormalizerConfig(
            unicodeNormalize = true,
            lowercase = false,
            normalizeWhitespace = true,
            removePunctuation = false,
            removeStopWords = false
        )
    }
}
