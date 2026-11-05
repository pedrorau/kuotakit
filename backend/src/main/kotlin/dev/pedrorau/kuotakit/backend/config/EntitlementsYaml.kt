/*
 * Copyright 2026 Pedro Rau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.pedrorau.kuotakit.backend.config

import dev.pedrorau.kuotakit.backend.domain.EntitlementDefinition
import dev.pedrorau.kuotakit.backend.domain.EntitlementsConfig
import dev.pedrorau.kuotakit.backend.domain.OfferingDefinition
import dev.pedrorau.kuotakit.backend.domain.PackageDefinition
import dev.pedrorau.kuotakit.contracts.Store
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `entitlements.yaml` (docs/api-spec.md) into an [EntitlementsConfig]. Every problem is reported with the file,
 * line and column, because the developer edits this file by hand and a vague error is a lost afternoon.
 */
object EntitlementsYaml {
    private val STORE_KEYS = mapOf("play_store" to Store.PLAY_STORE, "app_store" to Store.APP_STORE)
    private val TOP_LEVEL_KEYS = listOf("entitlements", "current_offering", "offerings", "one_time_products")

    fun load(path: Path): EntitlementsConfig {
        if (!Files.isRegularFile(path)) throw ConfigException("The entitlements file $path does not exist")
        return parse(Files.readString(path), path.fileName.toString())
    }

    fun parse(
        text: String,
        source: String = "entitlements.yaml",
    ): EntitlementsConfig = Reader(source).read(text)

    private class Reader(
        private val source: String,
    ) {
        fun read(text: String): EntitlementsConfig {
            val root =
                try {
                    Compose(LoadSettings.builder().build()).composeString(text).orElse(null)
                } catch (e: YamlEngineException) {
                    throw ConfigException("$source: ${e.message}", e)
                }
            if (root == null) fail(null, "the file is empty")
            val top = mapping(root, "the file")
            val unknown = top.keys - TOP_LEVEL_KEYS
            unknown.firstOrNull()?.let {
                fail(top.getValue(it).first, "unknown key '$it' (expected ${TOP_LEVEL_KEYS.joinToString()})")
            }

            val entitlements = entitlements(required(top, "entitlements", root))
            val offerings = offerings(required(top, "offerings", root))
            val currentNode = required(top, "current_offering", root)
            val current = scalar(currentNode, "current_offering")
            if (current !in offerings) {
                fail(
                    currentNode,
                    "current_offering '$current' is not one of the offerings (${offerings.keys.joinToString()})",
                )
            }
            checkOfferedProductsGrantSomething(entitlements, offerings, top)
            val oneTime = top["one_time_products"]?.let { oneTimeProducts(it.second, entitlements) }.orEmpty()
            return EntitlementsConfig(entitlements, current, offerings, oneTime)
        }

        /** The products bought once. Like the offered ones, each must grant something: a typo here sells nothing. */
        private fun oneTimeProducts(
            node: Node,
            entitlements: Map<String, EntitlementDefinition>,
        ): Map<Store, Set<String>> {
            val result = linkedMapOf<Store, Set<String>>()
            mapping(node, "one_time_products").forEach { (storeKey, entry) ->
                val store = store(storeKey, entry.first)
                val nodes = sequence(entry.second, "one_time_products.$storeKey")
                if (nodes.isEmpty()) fail(entry.second, "the one-time product list of '$storeKey' is empty")
                val granting = entitlements.values.flatMap { it.products[store].orEmpty() }.toSet()
                result[store] =
                    nodes
                        .map { productNode ->
                            val product = scalar(productNode, "a product id")
                            if (product !in granting) {
                                fail(
                                    productNode,
                                    "one-time product '$product' ($storeKey) is not part of any entitlement",
                                )
                            }
                            product
                        }.toCollection(linkedSetOf())
            }
            return result
        }

        private fun entitlements(node: Node): Map<String, EntitlementDefinition> {
            val result = linkedMapOf<String, EntitlementDefinition>()
            mapping(node, "entitlements").forEach { (id, entry) ->
                val (idNode, value) = entry
                val fields = mapping(value, "entitlement '$id'")
                (fields.keys - "products").firstOrNull()?.let {
                    fail(fields.getValue(it).first, "unknown key '$it' in entitlement '$id' (expected products)")
                }
                val products = linkedMapOf<Store, Set<String>>()
                mapping(required(fields, "products", value), "products of '$id'").forEach { (storeKey, productEntry) ->
                    val store = store(storeKey, productEntry.first)
                    val ids =
                        sequence(
                            productEntry.second,
                            "products.$storeKey of '$id'",
                        ).map { scalar(it, "a product id") }
                    if (ids.isEmpty()) fail(productEntry.second, "the product list of '$storeKey' in '$id' is empty")
                    products[store] = ids.toCollection(linkedSetOf())
                }
                if (products.isEmpty()) fail(idNode, "entitlement '$id' has no products")
                result[id] = EntitlementDefinition(products)
            }
            return result
        }

        private fun offerings(node: Node): Map<String, OfferingDefinition> {
            val result = linkedMapOf<String, OfferingDefinition>()
            mapping(node, "offerings").forEach { (id, entry) ->
                val packages = linkedMapOf<String, PackageDefinition>()
                mapping(entry.second, "offering '$id'").forEach { (packageId, packageEntry) ->
                    val products = linkedMapOf<Store, String>()
                    mapping(packageEntry.second, "package '$packageId' of '$id'").forEach { (storeKey, productEntry) ->
                        products[store(storeKey, productEntry.first)] =
                            scalar(productEntry.second, "the product of '$storeKey'")
                    }
                    if (products.isEmpty()) {
                        fail(
                            packageEntry.first,
                            "package '$packageId' of offering '$id' sells no product",
                        )
                    }
                    packages[packageId] = PackageDefinition(products)
                }
                result[id] = OfferingDefinition(id, packages)
            }
            if (result.isEmpty()) fail(node, "there are no offerings")
            return result
        }

        /** A product sold in an offering that grants nothing is almost always a typo, and a typo means lost revenue. */
        private fun checkOfferedProductsGrantSomething(
            entitlements: Map<String, EntitlementDefinition>,
            offerings: Map<String, OfferingDefinition>,
            top: Map<String, Pair<Node, Node>>,
        ) {
            val granting =
                entitlements.values
                    .flatMap {
                        it.products.entries
                    }.groupBy({ it.key }, { it.value })
                    .mapValues { it.value.flatten().toSet() }
            offerings.values.forEach { offering ->
                offering.packages.forEach { (packageId, definition) ->
                    definition.products.forEach { (store, product) ->
                        if (product !in granting[store].orEmpty()) {
                            fail(
                                top.getValue("offerings").first,
                                "product '$product' (${STORE_KEYS.entries.first {
                                    it.value == store
                                }.key}) of package " +
                                    "'$packageId' in offering '${offering.id}' is not part of any entitlement",
                            )
                        }
                    }
                }
            }
        }

        private fun store(
            key: String,
            at: Node,
        ): Store =
            STORE_KEYS[key]
                ?: fail(at, "unknown store '$key' (use play_store or app_store)")

        /** The entries of a mapping by key, with their key node (for the position); duplicate keys are an error. */
        private fun mapping(
            node: Node,
            what: String,
        ): Map<String, Pair<Node, Node>> {
            if (node !is MappingNode) fail(node, "$what must be a mapping")
            val result = linkedMapOf<String, Pair<Node, Node>>()
            node.value.forEach { tuple ->
                val key = scalar(tuple.keyNode, "a key of $what")
                if (key in result) fail(tuple.keyNode, "duplicate key '$key' in $what")
                result[key] = tuple.keyNode to tuple.valueNode
            }
            return result
        }

        private fun sequence(
            node: Node,
            what: String,
        ): List<Node> {
            if (node !is SequenceNode) fail(node, "$what must be a list")
            return node.value
        }

        private fun scalar(
            node: Node,
            what: String,
        ): String {
            if (node !is ScalarNode) fail(node, "$what must be a plain value")
            return node.value
        }

        private fun required(
            fields: Map<String, Pair<Node, Node>>,
            key: String,
            parent: Node,
        ): Node = fields[key]?.second ?: fail(parent, "missing '$key'")

        private fun fail(
            node: Node?,
            message: String,
        ): Nothing {
            val mark = node?.startMark?.orElse(null)
            val where = if (mark != null) ":${mark.line + 1}:${mark.column + 1}" else ""
            throw ConfigException("$source$where: $message")
        }
    }
}
