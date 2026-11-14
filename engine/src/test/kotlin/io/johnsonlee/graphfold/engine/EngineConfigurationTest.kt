package io.johnsonlee.graphfold.engine

import java.nio.file.Path
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineConfigurationTest {
    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `a scenario budget must permit at least one configured fold scenario`(limit: Int) {
        val failure = assertFailsWith<IllegalArgumentException> { EngineConfiguration(Path.of("unused"), limit) }
        assertTrue("maximumScenarios must be positive" in failure.message.orEmpty())
    }
}
