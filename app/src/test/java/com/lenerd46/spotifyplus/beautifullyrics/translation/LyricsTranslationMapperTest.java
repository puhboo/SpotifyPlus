package com.lenerd46.spotifyplus.beautifullyrics.translation;

import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableMetadata;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsTranslationMapperTest {
    @Test
    public void reconstructLineCombinesSyllablesUntilWordBoundary() {
        List<SyllableMetadata> syllables = List.of(
                syllable("Nous", 0, 0.4, false),
                syllable("trou", 0.4, 0.7, true),
                syllable("verons", 0.7, 1.1, false),
                syllable("ce", 1.1, 1.3, false),
                syllable("monde", 1.3, 1.8, false));

        assertEquals("Nous trouverons ce monde", LyricsTranslationMapper.reconstructLine(syllables));
    }

    @Test
    public void mapTokensPreservesOrderAndUsesSourceGroupTimes() {
        List<SyllableMetadata> syllables = List.of(
                syllable("Las", 0, 0.4, false),
                syllable("cuatro", 0.4, 0.9, false),
                syllable("paredes", 0.9, 1.5, false),
                syllable("del", 1.5, 1.8, false),
                syllable("hogar", 1.8, 2.4, false));

        List<TimedTranslationToken> tokens = LyricsTranslationMapper.mapTokens(
                syllables, "The four walls of our home");

        assertEquals(List.of("The", "four", "walls", "of", "our", "home"),
                tokens.stream().map(token -> token.text).collect(java.util.stream.Collectors.toList()));
        assertEquals(0, tokens.get(0).startTime, 0.0001);
        assertEquals(2.4, tokens.get(tokens.size() - 1).endTime, 0.0001);

        double previousStart = -1;
        for (TimedTranslationToken token : tokens) {
            assertTrue(token.startTime >= previousStart);
            assertTrue(token.endTime >= token.startTime);
            previousStart = token.startTime;
        }
    }

    @Test
    public void emptyInputsProduceNoTimedTokens() {
        assertTrue(LyricsTranslationMapper.mapTokens(new ArrayList<>(), "Hello").isEmpty());
        assertTrue(LyricsTranslationMapper.mapTokens(
                List.of(syllable("Hola", 0, 1, false)), " ").isEmpty());
        assertFalse(LyricsTranslationMapper.reconstructLine(
                List.of(syllable("Hola", 0, 1, false))).isBlank());
    }

    @Test
    public void multipleTranslationsForOneSourceWordAnimateSequentially() {
        List<TimedTranslationToken> tokens = LyricsTranslationMapper.mapTokens(
                List.of(syllable("bellissima", 3, 5, false)), "very beautiful");

        assertEquals(2, tokens.size());
        assertEquals(3, tokens.get(0).startTime, 0.0001);
        assertEquals(tokens.get(0).endTime, tokens.get(1).startTime, 0.0001);
        assertTrue(tokens.get(0).endTime < 5);
        assertEquals(5, tokens.get(1).endTime, 0.0001);
    }

    private static SyllableMetadata syllable(String text, double start, double end, boolean partOfWord) {
        SyllableMetadata metadata = new SyllableMetadata();
        metadata.text = text;
        metadata.startTime = start;
        metadata.endTime = end;
        metadata.isPartOfWord = partOfWord;
        return metadata;
    }
}
