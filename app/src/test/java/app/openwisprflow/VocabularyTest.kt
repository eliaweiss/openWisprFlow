package app.openwisprflow

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyTest {

    @Test
    fun picksNameLikeTerms() {
        assertEquals(
            listOf("Amina", "NablaLabs", "PostgreSQL", "v2", "iPhone"),
            Vocabulary.candidates("Hi Amina, the NablaLabs PostgreSQL v2 migration is on my iPhone. Thanks!"),
        )
    }

    @Test
    fun dropsCommonCapitalizedWordsAndPunctuation() {
        assertEquals(listOf("Design"), Vocabulary.candidates("Send. Reply! Design review — Search"))
        assertEquals(listOf("O'Brien"), Vocabulary.candidates("'O'Brien'"))
    }

    @Test
    fun dictionaryFirstThenNearestDeduplicated() {
        val dictionary = listOf(DictionaryEntry("openWisprFlow"), DictionaryEntry("NablaLabs", listOf("nabla labs")))
        val nearby = listOf("Hi Amina, ", "nablalabs Design review", "Amina Cohen")
        assertEquals(
            listOf("openWisprFlow", "NablaLabs", "Amina", "Design", "Cohen"),
            Vocabulary.build(dictionary, nearby),
        )
    }

    @Test
    fun respectsTheCap() {
        val nearby = (1..300).map { "Term$it" }
        assertEquals(100, Vocabulary.build(emptyList(), nearby).size)
        assertEquals(listOf("A1", "Term1"), Vocabulary.build(listOf(DictionaryEntry("A1")), nearby, cap = 2))
    }

    @Test
    fun dictionaryTextFormRoundTrips() {
        val text = """
            NablaLabs = nabla labs, nabla lab
            openWisprFlow

            nablalabs = duplicate
            PostgreSQL =
        """.trimIndent()
        val entries = Dictionary.parse(text)
        assertEquals(
            listOf(
                DictionaryEntry("NablaLabs", listOf("nabla labs", "nabla lab")),
                DictionaryEntry("openWisprFlow"),
                DictionaryEntry("PostgreSQL"),
            ),
            entries,
        )
        assertEquals("NablaLabs = nabla labs, nabla lab\nopenWisprFlow\nPostgreSQL", Dictionary.format(entries))
        assertEquals(entries, Dictionary.fromJson(Dictionary.toJson(entries)))
    }
}
