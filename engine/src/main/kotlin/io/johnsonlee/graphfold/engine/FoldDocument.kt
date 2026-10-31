package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.johnsonlee.graphite.input.FoldRule

internal data class FoldScenario(val name: String, val rules: List<FoldRule>)

/** Parse the actual Graphite rule schema; Cypher remains a query, never a pattern we interpret. */
internal object FoldDocument {
    fun parse(document: String, maximumScenarios: Int): List<FoldScenario> {
        require(maximumScenarios > 0) { "maximumScenarios must be positive" }
        val root = readDocument(document) as? Map<*, *>
            ?: throw IllegalArgumentException("fold document root must be an object containing folds or scenarios")
        val explicit = root.containsKey("scenarios")
        val documents = if (explicit) {
            require(root.keys == setOf("scenarios")) { "scenario documents accept only scenarios at the root" }
            root["scenarios"] as? List<*> ?: throw IllegalArgumentException("scenarios must be a list")
        } else {
            require(root.keys.all { it in setOf("version", "folds") }) {
                "fold document accepts only version and folds at the root; use scenarios for named assumptions"
            }
            listOf(root + ("name" to "baseline"))
        }
        require(documents.isNotEmpty() && documents.size <= maximumScenarios) {
            "expected 1..$maximumScenarios explicitly enumerated scenarios"
        }
        val scenarios = documents.mapIndexed { index, entry ->
            parseScenario(entry, if (explicit) "scenarios[$index]" else "fold document")
        }
        require(scenarios.map { it.name }.distinct().size == scenarios.size) { "scenario names must be unique" }
        return scenarios
    }

    private fun readDocument(document: String): Any? {
        val factory = YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
        val mapper = ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        return try {
            mapper.readValue(document, Any::class.java)
        } catch (failure: JsonProcessingException) {
            throw IllegalArgumentException("Invalid fold YAML; expected exactly one document: ${failure.message}", failure)
        }
    }

    private fun parseScenario(entry: Any?, path: String): FoldScenario {
        val scenario = requireNotNull(entry as? Map<*, *>) { "$path must be an object" }
        require(scenario.keys.all { it in setOf("name", "version", "folds") }) { "$path: unknown scenario property" }
        require(!scenario.containsKey("version") || scenario["version"] == 1) { "$path: only fold version 1 is supported" }
        val name = requireNotNull(scenario["name"] as? String) { "$path.name must be a string" }
        require(name.isNotBlank()) { "$path.name must not be blank" }
        val folds = requireNotNull(scenario["folds"] as? List<*>) { "$path.folds must be a list" }
        require(folds.isNotEmpty()) { "$path.folds requires at least one fold rule" }
        return FoldScenario(name, folds.mapIndexed { index, rule -> parseRule(rule, "$path.folds[$index]") })
    }

    private fun parseRule(entry: Any?, path: String): FoldRule {
        val rule = entry as? Map<*, *> ?: throw IllegalArgumentException("$path must be an object")
        return try {
            FoldRule.parse(rule)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("$path: ${failure.message}", failure)
        }
    }
}
