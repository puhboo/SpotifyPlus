package com.lenerd46.spotifyplus.beautifullyrics.translation;

import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableMetadata;

import java.util.ArrayList;
import java.util.List;

public final class LyricsTranslationMapper {
    private LyricsTranslationMapper() {
    }

    public static String reconstructLine(List<SyllableMetadata> syllables) {
        List<SourceWord> words = sourceWords(syllables);
        StringBuilder line = new StringBuilder();
        for (SourceWord word : words) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word.text);
        }
        return line.toString().trim();
    }

    public static List<TimedTranslationToken> mapTokens(List<SyllableMetadata> sourceSyllables,
            String translatedLine) {
        List<SourceWord> sourceWords = sourceWords(sourceSyllables);
        List<String> targetTokens = tokenize(translatedLine);
        List<TimedTranslationToken> result = new ArrayList<>();

        if (sourceWords.isEmpty() || targetTokens.isEmpty()) {
            return result;
        }

        int sourceWeight = sourceWords.stream().mapToInt(word -> weight(word.text)).sum();
        int targetWeight = targetTokens.stream().mapToInt(LyricsTranslationMapper::weight).sum();
        int targetOffset = 0;
        int sourceIndex = 0;
        int sourceOffset = 0;
        List<Integer> sourceAssignments = new ArrayList<>();

        for (String token : targetTokens) {
            int tokenWeight = weight(token);
            double targetMidpoint = (targetOffset + tokenWeight / 2d) / targetWeight;

            while (sourceIndex < sourceWords.size() - 1) {
                int nextSourceOffset = sourceOffset + weight(sourceWords.get(sourceIndex).text);
                double sourceEnd = nextSourceOffset / (double) sourceWeight;
                if (targetMidpoint <= sourceEnd) {
                    break;
                }
                sourceOffset = nextSourceOffset;
                sourceIndex++;
            }

            sourceAssignments.add(sourceIndex);
            targetOffset += tokenWeight;
        }

        int tokenIndex = 0;
        while (tokenIndex < targetTokens.size()) {
            int assignedSourceIndex = sourceAssignments.get(tokenIndex);
            int groupEndIndex = tokenIndex + 1;
            while (groupEndIndex < targetTokens.size()
                    && sourceAssignments.get(groupEndIndex) == assignedSourceIndex) {
                groupEndIndex++;
            }

            SourceWord sourceWord = sourceWords.get(assignedSourceIndex);
            int groupWeight = 0;
            for (int i = tokenIndex; i < groupEndIndex; i++) {
                groupWeight += weight(targetTokens.get(i));
            }

            int groupOffset = 0;
            double sourceDuration = Math.max(0, sourceWord.endTime - sourceWord.startTime);
            for (int i = tokenIndex; i < groupEndIndex; i++) {
                String token = targetTokens.get(i);
                int tokenWeight = weight(token);
                double startTime = sourceWord.startTime
                        + sourceDuration * (groupOffset / (double) groupWeight);
                groupOffset += tokenWeight;
                double endTime = sourceWord.startTime
                        + sourceDuration * (groupOffset / (double) groupWeight);
                result.add(new TimedTranslationToken(token, startTime, endTime));
            }

            tokenIndex = groupEndIndex;
        }

        return result;
    }

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return tokens;
        }
        for (String token : line.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static List<SourceWord> sourceWords(List<SyllableMetadata> syllables) {
        List<SourceWord> words = new ArrayList<>();
        if (syllables == null || syllables.isEmpty()) {
            return words;
        }

        StringBuilder text = new StringBuilder();
        double startTime = 0;
        double endTime = 0;
        boolean hasWord = false;

        for (SyllableMetadata syllable : syllables) {
            if (syllable == null || syllable.text == null) {
                continue;
            }
            if (!hasWord) {
                startTime = syllable.startTime;
                hasWord = true;
            }
            text.append(syllable.text);
            endTime = syllable.endTime;

            if (!syllable.isPartOfWord) {
                addWord(words, text, startTime, endTime);
                text.setLength(0);
                hasWord = false;
            }
        }

        if (hasWord) {
            addWord(words, text, startTime, endTime);
        }
        return words;
    }

    private static void addWord(List<SourceWord> words, StringBuilder text, double startTime, double endTime) {
        String value = text.toString().trim();
        if (!value.isEmpty()) {
            words.add(new SourceWord(value, startTime, endTime));
        }
    }

    private static int weight(String value) {
        if (value == null || value.isEmpty()) {
            return 1;
        }
        int count = (int) value.codePoints().filter(Character::isLetterOrDigit).count();
        return Math.max(1, count);
    }

    private static final class SourceWord {
        final String text;
        final double startTime;
        final double endTime;

        SourceWord(String text, double startTime, double endTime) {
            this.text = text;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }
}
