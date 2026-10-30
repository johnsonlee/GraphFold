package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.ObjectMapper
import io.johnsonlee.graphite.input.FoldRule

internal data class FoldScenario(val name: String, val rules: List<FoldRule>)

/** Parse the actual Graphite rule schema; Cypher remains a query, never a pattern we interpret. */
internal object FoldDocument {
    fun parse(document: String, maximumScenarios: Int): List<FoldScenario> {
        val factory = YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
        val mapper = ObjectMapper(factory)
        val root = mapper.readValue(document, Map::class.java)
        val documents = if (root.containsKey("scenarios")) {
            require(root.keys == setOf("scenarios")) { "scenario documents accept only scenarios at the root" }
            root["scenarios"] as? List<*> ?: error("scenarios must be a list")
        } else {
            listOf(root + ("name" to "baseline"))
        }
        require(documents.isNotEmpty() && documents.size <= maximumScenarios) {
            "expected 1..$maximumScenarios explicitly enumerated scenarios"
        }
        val scenarios = documents.map { entry ->
            val scenario = entry as? Map<*, *> ?: error("each scenario must be an object")
            require(scenario.keys.all { it in setOf("name", "version", "folds") }) { "unknown scenario property" }
            require(!scenario.containsKey("version") || scenario["version"] == 1) { "only fold version 1 is supported" }
            val name = scenario["name"] as? String ?: error("scenario name required")
            require(name.isNotBlank()) { "scenario name must not be blank" }
            val folds = scenario["folds"] as? List<*> ?: error("folds must be a list")
            require(folds.isNotEmpty()) { "each scenario requires at least one fold rule" }
            FoldScenario(name, folds.map { FoldRule.parse(it as? Map<*, *> ?: error("fold must be an object")) })
        }
        require(scenarios.map { it.name }.distinct().size == scenarios.size) { "scenario names must be unique" }
        return scenarios
    }
}
