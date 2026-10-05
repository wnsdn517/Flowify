package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.processing.SongContext.Register;

import org.junit.Test;

import java.util.Arrays;

/** Rules checked against Google's real output for the .claude/lyrics_test songs. */
public class SongContextTest {
    @Test
    public void spellsOutLyricSlangBeforeSending() {
        assertEquals("Tastes like strawberries on a summer evening",
                SongContext.preprocess("Tastes like strawberries on a summer evenin'"));
        assertEquals("Even tried to bite my tongue when you start a fight",
                SongContext.preprocess("Even tried to bite my tongue when you start shit"));
        assertEquals("I don't know if I could ever live without it",
                SongContext.preprocess("I don't know if I could ever go without"));
        assertEquals("Watermelon sugar rush", SongContext.preprocess("Watermelon sugar high"));
        assertEquals("I'm going to want to", SongContext.preprocess("Imma wanna"));
        // Whole words only: "yarn" is not "you", "cuzco" is not "because".
        assertEquals("yarn in cuzco", SongContext.preprocess("yarn in cuzco"));
    }

    @Test
    public void koreanBecomesPlainSpeechThroughout() {
        assertEquals("그런데 아무것도 안 나오네. 그래서 설명할게",
                SongContext.postprocess("그런데 아무것도 안 나오네요. 그래서 설명하겠습니다.", "ko", Register.CASUAL));
        assertEquals("숨을 들이쉬고 내쉬어",
                SongContext.postprocess("숨을 들이쉬고 내쉬세요", "ko", Register.CASUAL));
        assertEquals("이제 넌 내 친구들에게 질문을 문자로 보내고 있어",
                SongContext.postprocess("이제 당신은 내 친구들에게 질문을 문자로 보내고 있어요", "ko", Register.CASUAL));
        assertEquals("그냥 생각만 하는 중이야",
                SongContext.postprocess("그냥 생각만 하는 중이에요", "ko", Register.CASUAL));
        // Nouns ending in 요 keep it.
        assertEquals("네가 필요", SongContext.postprocess("네가 필요", "ko", Register.CASUAL));
    }

    @Test
    public void japaneseBecomesPlainWithThePronounTheSongCallsFor() {
        assertEquals("君のお腹と夏の気分が欲しい",
                SongContext.postprocess("あなたのお腹と夏の気分が欲しいです", "ja", Register.CASUAL));
        assertEquals("彼らはそもそもお前のことさえ好きじゃなかった",
                SongContext.postprocess("彼らはそもそもあなたのことさえ好きではありませんでした", "ja", Register.BLUNT));
        assertEquals("私はお前に夢中だったけど、もうやめた",
                SongContext.postprocess("私はあなたに夢中でしたが、もうやめました", "ja", Register.BLUNT));
        assertEquals("彼女はたった 2 日でそれを達成した、何というつながりだろう",
                SongContext.postprocess("彼女はたった 2 日でそれを達成しました、何というつながりでしょう", "ja", Register.BLUNT));
        assertEquals("でも何も伝わっていないので、詳しく説明させて",
                SongContext.postprocess("でも何も伝わっていないので、詳しく説明させてください", "ja", Register.CASUAL));
    }

    @Test
    public void russianImperativesAndEveryLanguagesFullStop() {
        assertEquals("Но ничего не проходит, поэтому позволь мне объяснить это",
                SongContext.postprocess("Но ничего не проходит, поэтому позвольте мне объяснить это", "ru", Register.CASUAL));
        assertEquals("Хочется больше ягод и ощущения лета",
                SongContext.postprocess("Хочется больше ягод и ощущения лета.", "ru", Register.CASUAL));
        assertEquals("and so...", SongContext.postprocess("and so...", "en", Register.CASUAL));
        // Chinese keeps Google's 你; only the trailing stop goes.
        assertEquals("我只是大声思考", SongContext.postprocess("我只是大声思考。", "zh-CN", Register.CASUAL));
    }

    @Test
    public void devotionalSongsKeepGooglesRegister() {
        assertEquals(Register.KEEP, SongContext.classify("\"Amazing Grace\" is a Christian hymn"));
        assertEquals(Register.CASUAL, SongContext.classify("a funk-pop song by Harry Styles"));
        assertEquals("주님 감사합니다.",
                SongContext.postprocess("주님 감사합니다.", "ko", Register.KEEP));
    }

    @Test
    public void swearingMakesTheWholeSongBlunt() {
        SongContext.noteLyrics("t-blunt", Arrays.asList("Fuck you and your mom", "la la"));
        SongContext.noteLyrics("t-soft", Arrays.asList("Baby, you're the end of June"));
        assertEquals(Register.BLUNT, SongContext.register(null, "t-blunt"));
        assertEquals(Register.CASUAL, SongContext.register(null, "t-soft"));
        // "class" contains "ass" but is not a swear.
        SongContext.noteLyrics("t-class", Arrays.asList("first class"));
        assertEquals(Register.CASUAL, SongContext.register(null, "t-class"));
    }
}
