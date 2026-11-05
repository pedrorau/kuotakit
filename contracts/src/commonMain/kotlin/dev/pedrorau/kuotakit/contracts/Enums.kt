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

package dev.pedrorau.kuotakit.contracts

import kotlinx.serialization.Serializable

/**
 * The store a purchase was made in. These identifiers are used in the API, in `entitlements.yaml` and in the SDK.
 *
 * Every enum of the API has an [UNKNOWN] member: a newer backend can add a value, and a client that does not know it
 * decodes it as [UNKNOWN] instead of failing to read the whole body. Backends never send it.
 */
@Serializable(with = StoreSerializer::class)
public enum class Store {
    /** Google Play, wire name `play_store`. */
    PLAY_STORE,

    /** The App Store, wire name `app_store`. */
    APP_STORE,

    /** A store this version does not know. */
    UNKNOWN,
}

internal object StoreSerializer : FallbackEnumSerializer<Store>(
    "dev.pedrorau.kuotakit.contracts.Store",
    mapOf(Store.PLAY_STORE to "play_store", Store.APP_STORE to "app_store"),
    Store.UNKNOWN,
)

/** How the current period of a subscription was obtained. Unknown values decode as [UNKNOWN]. */
@Serializable(with = PeriodTypeSerializer::class)
public enum class PeriodType {
    /** A regular paid period, wire name `normal`. */
    NORMAL,

    /** An introductory offer, wire name `intro`. */
    INTRO,

    /** A free trial, wire name `trial`. */
    TRIAL,

    /** A period type this version does not know. */
    UNKNOWN,
}

internal object PeriodTypeSerializer : FallbackEnumSerializer<PeriodType>(
    "dev.pedrorau.kuotakit.contracts.PeriodType",
    mapOf(PeriodType.NORMAL to "normal", PeriodType.INTRO to "intro", PeriodType.TRIAL to "trial"),
    PeriodType.UNKNOWN,
)

/**
 * Stable, machine-readable reason of an [ErrorBody]. Each code belongs to one HTTP status (docs/api-spec.md).
 * Unknown codes decode as [UNKNOWN]; the HTTP status still says what kind of failure it is.
 */
@Serializable(with = ErrorCodeSerializer::class)
public enum class ErrorCode {
    /** 400: the request body or a parameter is malformed. Wire name `invalid_payload`. */
    INVALID_PAYLOAD,

    /** 402: the store says the purchase is not valid. Wire name `purchase_invalid`. */
    PURCHASE_INVALID,

    /** 409: the purchase token is already linked to another user. Wire name `token_already_linked`. */
    TOKEN_ALREADY_LINKED,

    /** 502: the store API is unavailable; access is never granted (ADR 0003). Wire name `store_unavailable`. */
    STORE_UNAVAILABLE,

    /** 401: the API key is missing or wrong. Wire name `unauthorized`. */
    UNAUTHORIZED,

    /** 403: the key is valid but may not do this (an app key reading another user). Wire name `forbidden`. */
    FORBIDDEN,

    /** 404: the subscriber does not exist. Wire name `not_found`. */
    NOT_FOUND,

    /** A code this version does not know. */
    UNKNOWN,
}

internal object ErrorCodeSerializer : FallbackEnumSerializer<ErrorCode>(
    "dev.pedrorau.kuotakit.contracts.ErrorCode",
    mapOf(
        ErrorCode.INVALID_PAYLOAD to "invalid_payload",
        ErrorCode.PURCHASE_INVALID to "purchase_invalid",
        ErrorCode.TOKEN_ALREADY_LINKED to "token_already_linked",
        ErrorCode.STORE_UNAVAILABLE to "store_unavailable",
        ErrorCode.UNAUTHORIZED to "unauthorized",
        ErrorCode.FORBIDDEN to "forbidden",
        ErrorCode.NOT_FOUND to "not_found",
    ),
    ErrorCode.UNKNOWN,
)
