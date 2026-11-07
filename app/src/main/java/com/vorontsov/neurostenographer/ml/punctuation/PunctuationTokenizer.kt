package com.vorontsov.neurostenographer.ml.punctuation

/**
 * Faithful Kotlin port of the model's HF tokenizers pipeline
 * (Horizon-Labs/punctuation-restoration-small, tokenizer.json):
 *
 *  1. normalizer: Replace " " -> "\u25C1" (▁)
 *  2. pre-tokenizer: Metaspace(replacement=▁, prepend_scheme=always, split=true)
 *     - split on ▁, prefix every piece with ▁
 *  3. model: BPE with byte_fallback and 256k vocab, greedy lowest-rank merges
 *
 * Special tokens are NOT added here — windows add <bos>/<eos> explicitly,
 * mirroring the reference punctuate.py (add_special_tokens=False + manual
 * specials).
 */
class PunctuationTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToToken: Array<String>,
    private val pairRank: HashMap<Long, Int>,
) {

    data class Encoded(
        val ids: IntArray,
        /** wordOfToken[k] = index of the word token k belongs to. */
        val wordOfToken: IntArray,
    )

    /** Tokenizes words joined by single spaces, in order. */
    fun encodeWords(words: List<String>): Encoded {
        val ids = ArrayList<Int>(words.size * 2 + 8)
        val wordOf = ArrayList<Int>(words.size * 2 + 8)
        for ((wordIndex, word) in words.withIndex()) {
            // Normalize + Metaspace: split the space-normalized word on ▁ and
            // BPE-encode each ▁-prefixed piece. Single-space joining between
            // words keeps the mapping to the reference text tokenization.
            val normalized = word.replace(' ', '▁')
            var start = 0
            while (start <= normalized.length) {
                val splitAt = normalized.indexOf('▁', start)
                val end = if (splitAt < 0) normalized.length else splitAt
                if (end > start) bpe("▁" + normalized.substring(start, end), wordIndex, ids, wordOf)
                if (splitAt < 0) break
                start = splitAt + 1
            }
        }
        return Encoded(ids.toIntArray(), wordOf.toIntArray())
    }

    private fun bpe(piece: String, wordIndex: Int, ids: MutableList<Int>, wordOf: MutableList<Int>) {
        val symbols = ArrayList<Int>(piece.length)
        var index = 0
        while (index < piece.length) {
            val char = piece[index]
            val known = vocab[char.toString()]
            if (known != null) {
                symbols += known
            } else {
                // byte_fallback: encode the char's UTF-8 bytes as <0xXX>.
                // The mmBERT vocab only ships the byte tokens seen in
                // training — an absent one degrades to <unk>, never a crash.
                for (byte in char.toString().toByteArray(Charsets.UTF_8)) {
                    symbols += vocab[byteToken(byte)] ?: UNK_ID
                }
            }
            index++
        }
        // Greedy merges by ascending rank, exactly like the tokenizers BPE.
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestPos = -1
            for (position in 0 until symbols.size - 1) {
                val rank = pairRank[pack(symbols[position], symbols[position + 1])]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestPos = position
                }
            }
            if (bestPos < 0) break
            val merged = vocab[idToToken[symbols[bestPos]] + idToToken[symbols[bestPos + 1]]]
                ?: break
            symbols[bestPos] = merged
            symbols.removeAt(bestPos + 1)
        }
        ids.addAll(symbols)
        repeat(symbols.size) { wordOf.add(wordIndex) }
    }

    companion object {
        const val PAD_ID = 0
        const val EOS_ID = 1
        const val BOS_ID = 2
        const val UNK_ID = 3

        private fun byteToken(byte: Byte): String =
            "<0x" + (byte.toInt() and 0xFF).toString(16).padStart(2, '0') + ">"

        private fun pack(first: Int, second: Int): Long =
            (first.toLong() shl 32) or (second.toLong() and 0xFFFF_FFFFL)

        /**
         * Loads from the extracted `vocab.txt` (`token<TAB>id` lines) and
         * `merges.txt` (`tokenA<TAB>tokenB` lines, rank = line number).
         */
        fun load(vocabText: String, mergesText: String): PunctuationTokenizer {
            val vocab = HashMap<String, Int>(260_000)
            for (line in vocabText.lineSequence()) {
                if (line.isEmpty()) continue
                val tab = line.lastIndexOf('\t')
                vocab[unescape(line.substring(0, tab))] = line.substring(tab + 1).toInt()
            }
            val idToToken = Array(vocab.size) { "<unk>" }
            for ((token, id) in vocab) idToToken[id] = token

            val pairRank = HashMap<Long, Int>(580_700)
            mergesText.lineSequence().forEachIndexed { rank, line ->
                if (line.isEmpty()) return@forEachIndexed
                val tab = line.indexOf('\t')
                val first = vocab[unescape(line.substring(0, tab))] ?: return@forEachIndexed
                val second = vocab[unescape(line.substring(tab + 1))] ?: return@forEachIndexed
                pairRank[pack(first, second)] = rank
            }
            return PunctuationTokenizer(vocab, idToToken, pairRank)
        }

        private fun unescape(text: String): String {
            if ('\\' !in text) return text
            val out = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '\\' && index + 1 < text.length) {
                    when (text[index + 1]) {
                        't' -> { out.append('\t'); index += 2; continue }
                        'n' -> { out.append('\n'); index += 2; continue }
                        'r' -> { out.append('\r'); index += 2; continue }
                        '\\' -> { out.append('\\'); index += 2; continue }
                    }
                }
                out.append(char)
                index++
            }
            return out.toString()
        }
    }
}
